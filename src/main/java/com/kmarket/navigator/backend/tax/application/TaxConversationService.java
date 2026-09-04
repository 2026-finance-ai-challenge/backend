package com.kmarket.navigator.backend.tax.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kmarket.navigator.backend.chat.application.port.ChatRoomRepository;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.chat.domain.ChatRoom;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.application.port.TaxConversationRepository;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentRepository;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentStorage;
import com.kmarket.navigator.backend.tax.domain.TaxConversationState;
import com.kmarket.navigator.backend.tax.domain.TaxGuideAction;

@Service
public class TaxConversationService {
	private final TaxConversationRepository repository;
	private final ChatRoomRepository rooms;
	private final TaxDocumentRepository documents;
	private final TaxDocumentStorage storage;
	private final TaxEligibilityService eligibility;
	public TaxConversationService(TaxConversationRepository repository, ChatRoomRepository rooms,
		TaxDocumentRepository documents, TaxDocumentStorage storage, TaxEligibilityService eligibility) {
		this.repository = repository; this.rooms = rooms; this.documents = documents;
		this.storage = storage; this.eligibility = eligibility;
	}
	@Transactional
	public ChatRoom ensureRoom(UUID userId, String locale) {
		// 생성·업로드·재시작을 사용자 단위로 직렬화하여 세무방 중복과 삭제 중 업로드를 막는다.
		repository.lockUser(userId);
		ChatRoom room = repository.findRoomId(userId).flatMap(id -> rooms.findOwned(userId, id))
			.orElseGet(() -> rooms.create(userId, "Tax assessment",
				new ChatContext(ChatContextType.TAX_GUIDE, null, null, "Dividend withholding tax"), Instant.now()));
		repository.initialize(room.id(), locale);
		return room;
	}
	@Transactional
	public TaxConversationState get(UUID userId, String locale) {
		ChatRoom room = ensureRoom(userId, locale);
		TaxConversationState state = repository.state(room.id());
		if (state.eligibility() == null) {
			var previous = documents.findAll(userId);
			if (!previous.isEmpty()) {
				var document = previous.getFirst();
				repository.saveEligibility(room.id(), state.locale(), eligibility.check(document.expectedResidencyCountry(), document.investorType()));
				state = repository.state(room.id());
			}
		}
		return state;
	}
	@Transactional
	public TaxConversationState assess(UUID userId, String country, InvestorType investor, String locale) {
		ChatRoom room = ensureRoom(userId, locale);
		if (!documents.findAll(userId).isEmpty()) throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED);
		repository.saveEligibility(room.id(), locale, eligibility.check(country, investor));
		repository.touch(userId);
		return repository.state(room.id());
	}
	@Transactional
	public TaxConversationState advance(UUID userId, TaxGuideAction action) {
		ChatRoom room = ensureRoom(userId, "en");
		TaxConversationState state = repository.state(room.id());
		if (state.eligibility() == null) throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED);
		int guideDepth = state.guideDepth();
		boolean verificationStarted = state.verificationStarted();
		switch (action) {
			case SHOW_GUIDE -> guideDepth = Math.max(guideDepth, 1);
			case SHOW_MORE_DETAIL -> {
				if (guideDepth < 1) throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED);
				guideDepth = 2;
			}
			case START_VERIFICATION -> verificationStarted = true;
		}
		repository.saveGuideProgress(room.id(), guideDepth, verificationStarted);
		repository.touch(userId);
		return repository.state(room.id());
	}
	@Transactional
	public TaxConversationState restart(UUID userId, UUID roomId, String locale) {
		delete(userId, roomId);
		return get(userId, locale);
	}
	@Transactional
	public void delete(UUID userId, UUID roomId) {
		repository.lockUser(userId);
		if (!repository.findRoomId(userId).filter(roomId::equals).isPresent()) throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
		for (var document : documents.findAllIncludingDeleted(userId)) {
			if (!document.storageKey().startsWith("purged/")) storage.delete(document.storageKey());
		}
		documents.deleteAll(userId);
		repository.deleteRoom(userId, roomId);
	}
}
