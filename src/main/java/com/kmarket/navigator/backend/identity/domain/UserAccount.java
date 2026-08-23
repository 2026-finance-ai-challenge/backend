package com.kmarket.navigator.backend.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
	UUID id,
	String loginId,
	String passwordHash,
	String nationality,
	InvestorType investorType,
	TaxVerificationStatus taxVerificationStatus,
	Instant createdAt
) {
}
