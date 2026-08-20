package com.kmarket.navigator.backend.disclosure.application.port;

public record DocumentJob(
	String receiptNumber,
	String stockCode,
	String stockNameKo,
	int attempts
) {
}
