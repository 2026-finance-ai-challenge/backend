package com.kmarket.navigator.backend.disclosure.domain;

import java.time.LocalDate;

public record DisclosureVersion(
	String receiptNumber,
	String titleKo,
	LocalDate filedDate,
	boolean correction,
	String correctionOfReceiptNumber,
	boolean current
) {
}
