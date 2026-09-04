package com.kmarket.navigator.backend.tax.domain;

import java.util.UUID;

public record TaxConversationState(
	UUID roomId, String locale, TaxEligibilityResult eligibility, TaxDocumentComparison comparison,
	int guideDepth, boolean verificationStarted
) { }
