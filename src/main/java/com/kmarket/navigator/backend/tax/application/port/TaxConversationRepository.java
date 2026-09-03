package com.kmarket.navigator.backend.tax.application.port;

import java.util.Optional;
import java.util.UUID;
import com.kmarket.navigator.backend.tax.domain.TaxConversationState;
import com.kmarket.navigator.backend.tax.domain.TaxEligibilityResult;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentComparison;

public interface TaxConversationRepository {
	void lockUser(UUID userId);
	Optional<UUID> findRoomId(UUID userId);
	TaxConversationState state(UUID roomId);
	void initialize(UUID roomId, String locale);
	void saveEligibility(UUID roomId, String locale, TaxEligibilityResult result);
	void saveComparison(UUID roomId, TaxDocumentComparison result);
	void touch(UUID userId);
	void deleteRoom(UUID userId, UUID roomId);
}
