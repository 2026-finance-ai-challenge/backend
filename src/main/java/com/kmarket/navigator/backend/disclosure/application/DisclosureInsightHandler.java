package com.kmarket.navigator.backend.disclosure.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureInsightGateway;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsight;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightEvidence;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightGeneration;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Service
public class DisclosureInsightHandler {

	private static final int MAX_EVIDENCE_ITEMS = 100;
	private static final int MAX_ITEM_CHARACTERS = 6_000;
	private static final int MAX_TOTAL_CHARACTERS = 60_000;
	private static final String SUMMARY_PROMPT_VERSION = "filing-summary-v3";
	private final DisclosureRepository repository;
	private final DisclosureInsightGateway gateway;
	private final DisclosureInsightGenerationGuard generationGuard;
	private final Clock clock;

	@Autowired
	public DisclosureInsightHandler(
		DisclosureRepository repository,
		DisclosureInsightGateway gateway,
		DisclosureInsightGenerationGuard generationGuard
	) {
		this(repository, gateway, generationGuard, Clock.systemUTC());
	}

	DisclosureInsightHandler(
		DisclosureRepository repository,
		DisclosureInsightGateway gateway,
		DisclosureInsightGenerationGuard generationGuard,
		Clock clock
	) {
		this.repository = repository;
		this.gateway = gateway;
		this.generationGuard = generationGuard;
		this.clock = clock;
	}

	public DisclosureInsight find(String receiptNumber) {
		DisclosureDetail detail = detail(receiptNumber);
		return repository.findInsight(receiptNumber, DisclosureContentVersion.calculate(detail))
			.filter(insight -> SUMMARY_PROMPT_VERSION.equals(insight.promptVersion()))
			.orElseThrow(() -> new BusinessException(ErrorCode.DISCLOSURE_INSIGHT_NOT_READY));
	}

	public DisclosureInsight generate(String receiptNumber) {
		DisclosureDetail detail = detail(receiptNumber);
		String contentVersion = DisclosureContentVersion.calculate(detail);
		var existing = repository.findInsight(receiptNumber, contentVersion)
			.filter(insight -> SUMMARY_PROMPT_VERSION.equals(insight.promptVersion()));
		if (existing.isPresent()) {
			return existing.get();
		}
		DisclosureInsightGenerationGuard.Guard acquired = generationGuard.tryAcquire(contentVersion);
		if (acquired == null) {
			return awaitCached(receiptNumber, contentVersion);
		}
		try (acquired) {
			return generateLocked(receiptNumber, detail, contentVersion);
		}
	}

	private DisclosureInsight generateLocked(
		String receiptNumber,
		DisclosureDetail detail,
		String contentVersion
	) {
		var cached = repository.findInsight(receiptNumber, contentVersion)
			.filter(insight -> SUMMARY_PROMPT_VERSION.equals(insight.promptVersion()));
		if (cached.isPresent()) {
			return cached.get();
		}
		Map<String, DisclosureSection> allowed = evidence(detail);
		if (allowed.isEmpty()) {
			throw new BusinessException(ErrorCode.DISCLOSURE_DOCUMENT_NOT_READY);
		}
		DisclosureInsightGeneration generated = gateway.summarize(
			receiptNumber,
			detail.titleKo(),
			allowed.entrySet().stream()
				.map(entry -> new DisclosureInsightEvidence(
					entry.getKey(),
					entry.getValue().heading(),
					content(entry.getValue())
				))
				.toList()
		);
		List<UUID> sources = generated.evidenceIds().stream()
			.distinct()
			.map(allowed::get)
			.filter(java.util.Objects::nonNull)
			.map(DisclosureSection::id)
			.toList();
		boolean sufficient = generated.sufficientEvidence() && !sources.isEmpty();
		DisclosureInsight insight = new DisclosureInsight(
			receiptNumber,
			contentVersion,
			sufficient ? generated.what() : null,
			sufficient ? generated.why() : null,
			sufficient ? generated.impact() : null,
			sources,
			sufficient,
			sufficient ? null : refusalReason(generated, sources),
			generated.modelId(),
			generated.promptVersion(),
			Instant.now(clock),
			sufficient ? generated.whatKo() : null,
			sufficient ? generated.whyKo() : null,
			sufficient ? generated.impactKo() : null
		);
		repository.saveInsight(insight);
		return insight;
	}

	private DisclosureInsight awaitCached(String receiptNumber, String contentVersion) {
		long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
		while (System.nanoTime() < deadline) {
			var cached = repository.findInsight(receiptNumber, contentVersion)
				.filter(insight -> SUMMARY_PROMPT_VERSION.equals(insight.promptVersion()));
			if (cached.isPresent()) {
				return cached.get();
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new BusinessException(ErrorCode.DISCLOSURE_INSIGHT_NOT_READY);
	}

	private DisclosureDetail detail(String receiptNumber) {
		DisclosureDetail detail = repository.findByReceiptNumber(receiptNumber)
			.orElseThrow(() -> new BusinessException(ErrorCode.DISCLOSURE_NOT_FOUND));
		if (detail.documents().isEmpty()) {
			repository.requestIndexing(receiptNumber);
			throw new BusinessException(ErrorCode.DISCLOSURE_DOCUMENT_NOT_READY);
		}
		return detail;
	}

	private Map<String, DisclosureSection> evidence(DisclosureDetail detail) {
		Map<String, DisclosureSection> result = new LinkedHashMap<>();
		int totalCharacters = 0;
		for (var document : detail.documents()) {
			for (DisclosureSection section : document.sections()) {
				String content = content(section);
				if (content.isBlank()) {
					continue;
				}
				int accepted = Math.min(content.length(), MAX_ITEM_CHARACTERS);
				if (result.size() >= MAX_EVIDENCE_ITEMS
					|| totalCharacters + accepted > MAX_TOTAL_CHARACTERS) {
					return result;
				}
				result.put("S" + (result.size() + 1), section);
				totalCharacters += accepted;
			}
		}
		return result;
	}

	private String content(DisclosureSection section) {
		String value = section.text();
		if ((value == null || value.isBlank()) && section.tableData() != null) {
			value = section.tableData();
		}
		if (value == null) {
			return "";
		}
		return value.substring(0, Math.min(value.length(), MAX_ITEM_CHARACTERS));
	}

	private String refusalReason(DisclosureInsightGeneration generated, List<UUID> sources) {
		if (generated.sufficientEvidence() && sources.isEmpty()) {
			return "The generated summary did not contain a verifiable filing source.";
		}
		return generated.refusalReason() == null || generated.refusalReason().isBlank()
			? "The filing evidence is insufficient for a reliable summary."
			: generated.refusalReason();
	}

}
