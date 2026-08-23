package com.kmarket.navigator.backend.chat.presentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmarket.navigator.backend.chat.application.ChatRoomService;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.chat.domain.ChatRoom;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;

@Validated
@RestController
@RequestMapping("/api/v1/me/chats")
public class ChatRoomController {

	private final ChatRoomService service;

	public ChatRoomController(ChatRoomService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<ChatRoomResponse> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreateChatRoomRequest body
	) {
		return ResponseEntity.status(201)
			.cacheControl(CacheControl.noStore())
			.body(ChatRoomResponse.from(service.create(user, body.contextType(), body.referenceId())));
	}

	@GetMapping
	public ResponseEntity<List<ChatRoomResponse>> findAll(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(required = false) @Size(max = 80) String query,
		@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
	) {
		return noStore(service.findAll(user, query, limit).stream().map(ChatRoomResponse::from).toList());
	}

	@GetMapping("/{roomId}")
	public ResponseEntity<ChatRoomResponse> findOne(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId
	) {
		return noStore(ChatRoomResponse.from(service.findOne(user, roomId)));
	}

	@PutMapping("/{roomId}/name")
	public ResponseEntity<ChatRoomResponse> rename(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId,
		@Valid @RequestBody RenameChatRoomRequest body
	) {
		return noStore(ChatRoomResponse.from(
			service.rename(user, roomId, body.name(), body.expectedVersion())
		));
	}

	@DeleteMapping("/{roomId}")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID roomId
	) {
		service.delete(user, roomId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	public record CreateChatRoomRequest(
		@NotNull ChatContextType contextType,
		@Size(max = 128) String referenceId
	) {
	}

	public record RenameChatRoomRequest(
		@NotNull @Size(min = 1, max = 80) String name,
		@Min(0) long expectedVersion
	) {
	}

	public record ChatRoomResponse(
		UUID id,
		String name,
		ContextResponse context,
		long version,
		Instant createdAt,
		Instant updatedAt,
		Instant lastMessageAt
	) {
		static ChatRoomResponse from(ChatRoom room) {
			return new ChatRoomResponse(
				room.id(),
				room.name(),
				ContextResponse.from(room.context()),
				room.version(),
				room.createdAt(),
				room.updatedAt(),
				room.lastMessageAt()
			);
		}
	}

	public record ContextResponse(
		ChatContextType type,
		String referenceId,
		String version,
		String title
	) {
		static ContextResponse from(ChatContext context) {
			return new ContextResponse(
				context.type(),
				context.referenceId(),
				context.version(),
				context.title()
			);
		}
	}
}
