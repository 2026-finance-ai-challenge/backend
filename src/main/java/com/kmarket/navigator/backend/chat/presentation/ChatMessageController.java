package com.kmarket.navigator.backend.chat.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmarket.navigator.backend.chat.application.ChatMessageService;
import com.kmarket.navigator.backend.chat.domain.ChatCitation;
import com.kmarket.navigator.backend.chat.domain.ChatGeneration;
import com.kmarket.navigator.backend.chat.domain.ChatGenerationStatus;
import com.kmarket.navigator.backend.chat.domain.ChatMessage;
import com.kmarket.navigator.backend.chat.domain.ChatMessageRole;
import com.kmarket.navigator.backend.chat.domain.ChatSubmission;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;

@Validated
@RestController
@RequestMapping("/api/v1/me/chats/{roomId}")
public class ChatMessageController {

	private final ChatMessageService service;

	public ChatMessageController(ChatMessageService service) {
		this.service = service;
	}

	@PostMapping("/messages")
	public ResponseEntity<SubmissionResponse> submit(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId,
		@Valid @RequestBody SubmitMessageRequest body
	) {
		ChatSubmission submission = service.submit(
			user,
			roomId,
			body.clientMessageId(),
			body.content(),
			body.selectedSectionId(),
			body.selectedText()
		);
		return ResponseEntity.accepted()
			.cacheControl(CacheControl.noStore())
			.body(new SubmissionResponse(
				MessageResponse.from(submission.userMessage()),
				GenerationResponse.from(submission.generation())
			));
	}

	@GetMapping("/messages")
	public ResponseEntity<List<MessageResponse>> messages(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId,
		@RequestParam(defaultValue = "0") @Min(0) long afterSequence,
		@RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit
	) {
		return noStore(service.messages(user, roomId, afterSequence, limit).stream()
			.map(MessageResponse::from)
			.toList());
	}

	@GetMapping("/generations/{generationId}")
	public ResponseEntity<GenerationResponse> generation(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId,
		@PathVariable UUID generationId
	) {
		return noStore(GenerationResponse.from(service.generation(user, roomId, generationId)));
	}

	@PostMapping("/generations/{generationId}/stop")
	public ResponseEntity<GenerationResponse> stop(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId,
		@PathVariable UUID generationId
	) {
		return noStore(GenerationResponse.from(service.stop(user, roomId, generationId)));
	}

	@PostMapping("/generations/{generationId}/retry")
	public ResponseEntity<GenerationResponse> retry(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId,
		@PathVariable UUID generationId
	) {
		return noStore(GenerationResponse.from(service.retry(user, roomId, generationId)));
	}

	private <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	public record SubmitMessageRequest(
		@NotNull UUID clientMessageId,
		@NotBlank @Size(max = 4_000) String content,
		UUID selectedSectionId,
		@Size(max = 2_000) String selectedText
	) {
		@AssertTrue(message = "selectedSectionId requires selectedText")
		public boolean selectionComplete() {
			return selectedSectionId == null || selectedText != null && !selectedText.isBlank();
		}
	}

	public record SubmissionResponse(MessageResponse userMessage, GenerationResponse generation) {
	}

	public record GenerationResponse(
		UUID id,
		UUID userMessageId,
		UUID regenerationOfMessageId,
		ChatGenerationStatus status,
		int attempts,
		String errorCode,
		boolean retryable,
		Instant createdAt,
		Instant updatedAt,
		Instant completedAt
	) {
		static GenerationResponse from(ChatGeneration generation) {
			return new GenerationResponse(
				generation.id(),
				generation.userMessageId(),
				generation.regenerationOfMessageId(),
				generation.status(),
				generation.attempts(),
				generation.lastErrorCode(),
				generation.retryable(),
				generation.createdAt(),
				generation.updatedAt(),
				generation.completedAt()
			);
		}
	}

	public record MessageResponse(
		UUID id,
		long sequence,
		ChatMessageRole role,
		String content,
		UUID replyToMessageId,
		List<CitationResponse> citations,
		boolean insufficientEvidence,
		String refusalReason,
		String disclaimer,
		BigDecimal confidence,
		String modelId,
		String promptVersion,
		String requestId,
		Instant createdAt
	) {
		static MessageResponse from(ChatMessage message) {
			return new MessageResponse(
				message.id(),
				message.sequence(),
				message.role(),
				message.content(),
				message.replyToMessageId(),
				message.citations().stream().map(CitationResponse::from).toList(),
				message.insufficientEvidence(),
				message.refusalReason(),
				message.disclaimer(),
				message.confidence(),
				message.modelId(),
				message.promptVersion(),
				message.requestId(),
				message.createdAt()
			);
		}
	}

	public record CitationResponse(
		String id,
		String sourceType,
		String referenceId,
		String title,
		String excerpt,
		String url,
		Instant asOf,
		List<UUID> sectionIds,
		String titleEn,
		String titleKo
	) {
		static CitationResponse from(ChatCitation citation) {
			return new CitationResponse(
				citation.id(),
				citation.sourceType(),
				citation.referenceId(),
				citation.title(),
				citation.excerpt(),
				citation.url(),
				citation.asOf(),
				citation.sectionIds(),
				citation.titleEn(),
				citation.titleKo()
			);
		}
	}
}
