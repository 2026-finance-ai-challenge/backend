package com.kmarket.navigator.backend.chat.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.chat.application.port.AgentGateway;
import com.kmarket.navigator.backend.chat.application.port.ChatMessageRepository;
import com.kmarket.navigator.backend.chat.domain.AgentEvidence;
import com.kmarket.navigator.backend.chat.domain.ChatCitation;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.disclosure.application.DisclosureContentVersion;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQuestionHandler;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class ChatGenerationWorker {

	private static final Logger log = LoggerFactory.getLogger(ChatGenerationWorker.class);
	private static final int MAX_ATTEMPTS = 3;
	private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);
	private final ChatMessageRepository repository;
	private final AgentGateway agentGateway;
	private final AgentEvidenceProvider evidenceProvider;
	private final AgentSafetyIdentifier safetyIdentifier;
	private final DisclosureQuestionHandler disclosureQuestionHandler;
	private final DisclosureRepository disclosureRepository;
	private final Clock clock;
	private final String workerId = "chat-" + UUID.randomUUID();

	public ChatGenerationWorker(
		ChatMessageRepository repository,
		AgentGateway agentGateway,
		AgentEvidenceProvider evidenceProvider,
		AgentSafetyIdentifier safetyIdentifier,
		DisclosureQuestionHandler disclosureQuestionHandler,
		DisclosureRepository disclosureRepository
	) {
		this.repository = repository;
		this.agentGateway = agentGateway;
		this.evidenceProvider = evidenceProvider;
		this.safetyIdentifier = safetyIdentifier;
		this.disclosureQuestionHandler = disclosureQuestionHandler;
		this.disclosureRepository = disclosureRepository;
		this.clock = Clock.systemUTC();
	}

	@Scheduled(
		fixedDelayString = "${kmarket.chat.generation-interval:1s}",
		initialDelayString = "${kmarket.chat.generation-initial-delay:30s}"
	)
	@SchedulerLock(name = "chat-generation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT0.1S")
	public void process() {
		Instant now = Instant.now(clock);
		for (ChatGenerationTask task : repository.claim(
			workerId,
			5,
			now,
			now.minus(PROCESSING_TIMEOUT)
		)) {
			process(task);
		}
	}

	private void process(ChatGenerationTask task) {
		try {
			CompletedChatAnswer answer = task.context().type() == ChatContextType.FILING
				? filingAnswer(task)
				: agentAnswer(task);
			repository.complete(task.generationId(), answer, Instant.now(clock));
		}
		catch (BusinessException exception) {
			fail(task, exception.errorCode().code(), retryable(exception.errorCode()));
		}
		catch (RuntimeException exception) {
			fail(task, exception.getClass().getSimpleName(), true);
		}
	}

	private CompletedChatAnswer agentAnswer(ChatGenerationTask task) {
		List<AgentEvidence> evidence = evidenceProvider.evidence(task.context());
		Map<String, AgentEvidence> allowed = new LinkedHashMap<>();
		evidence.forEach(item -> allowed.put(item.id(), item));
		var generated = agentGateway.answer(
			task.context(),
			task.question(),
			task.history(),
			evidence,
			safetyIdentifier.from(task.userId())
		);
		List<ChatCitation> citations = generated.evidenceIds().stream()
			.distinct()
			.map(allowed::get)
			.filter(java.util.Objects::nonNull)
			.map(item -> new ChatCitation(
				item.id(),
				task.context().type().name(),
				item.referenceId(),
				item.title(),
				excerpt(item.content()),
				item.url(),
				item.asOf(),
				List.of()
			))
			.toList();
		boolean invalidEvidence = task.context().type() != ChatContextType.GENERAL
			&& !evidence.isEmpty()
			&& !generated.insufficientEvidence()
			&& citations.isEmpty();
		return new CompletedChatAnswer(
			invalidEvidence
				? "I could not verify this answer against the available server evidence."
				: generated.answer(),
			citations,
			generated.insufficientEvidence() || invalidEvidence,
			invalidEvidence
				? "The generated answer did not contain a verifiable source."
				: generated.refusalReason(),
			generated.disclaimer(),
			generated.confidence(),
			generated.modelId(),
			generated.promptVersion(),
			title(generated.suggestedRoomName(), task.question()),
			task.generationId().toString()
		);
	}

	private CompletedChatAnswer filingAnswer(ChatGenerationTask task) {
		DisclosureDetail detail = disclosureRepository.findByReceiptNumber(task.context().referenceId())
			.orElseThrow(() -> new BusinessException(ErrorCode.DISCLOSURE_NOT_FOUND));
		if (!DisclosureContentVersion.calculate(detail).equals(task.context().version())) {
			throw new BusinessException(ErrorCode.CHAT_CONTEXT_STALE);
		}
		DisclosureQuestion.SelectedContext selected = selectedContext(task, detail);
		var answer = disclosureQuestionHandler.ask(
			task.context().referenceId(),
			new DisclosureQuestion(task.question(), selected)
		);
		List<ChatCitation> citations = answer.citations().stream()
			.map(citation -> new ChatCitation(
				citation.id(),
				"FILING",
				task.context().referenceId(),
				citation.heading(),
				citation.excerpt(),
				detail.officialUrl(),
				detail.detectedAt(),
				citation.sectionIds()
			))
			.toList();
		return new CompletedChatAnswer(
			answer.answer(),
			citations,
			answer.refused(),
			answer.refusalReason(),
			"For information only. The answer is limited to this filing version.",
			answer.refused() ? BigDecimal.ZERO : null,
			answer.model(),
			answer.promptVersion(),
			title(null, task.question()),
			task.generationId().toString()
		);
	}

	private DisclosureQuestion.SelectedContext selectedContext(
		ChatGenerationTask task,
		DisclosureDetail detail
	) {
		if (task.selectedSectionId() == null) {
			return null;
		}
		var section = detail.documents().stream()
			.flatMap(document -> document.sections().stream())
			.filter(candidate -> candidate.id().equals(task.selectedSectionId()))
			.findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CHAT_SELECTION));
		String source = section.text() == null || section.text().isBlank()
			? section.tableData()
			: section.text();
		if (source == null || !source.contains(task.selectedText())) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_SELECTION);
		}
		return new DisclosureQuestion.SelectedContext(section.id(), task.selectedText());
	}

	private void fail(ChatGenerationTask task, String errorCode, boolean canRetry) {
		boolean terminal = !canRetry || task.attempts() >= MAX_ATTEMPTS;
		long delaySeconds = Math.min(60, 5L << Math.max(0, task.attempts() - 1));
		Instant now = Instant.now(clock);
		repository.fail(
			task.generationId(),
			errorCode,
			terminal,
			now.plusSeconds(delaySeconds),
			now
		);
		log.warn(
			"Chat generation failed generationId={} contextType={} attempt={} code={} terminal={}",
			task.generationId(),
			task.context().type(),
			task.attempts(),
			errorCode,
			terminal
		);
	}

	private boolean retryable(ErrorCode errorCode) {
		return errorCode == ErrorCode.AI_SERVICE_UNAVAILABLE
			|| errorCode == ErrorCode.DISCLOSURE_INDEX_NOT_READY;
	}

	private String excerpt(String content) {
		return content.substring(0, Math.min(content.length(), 1_000));
	}

	private String title(String suggested, String question) {
		String candidate = suggested == null || suggested.isBlank() ? question : suggested;
		String normalized = candidate.replaceAll("\\s+", " ").strip();
		if (normalized.isBlank()) {
			return "New chat";
		}
		return normalized.substring(0, Math.min(normalized.length(), 80));
	}
}
