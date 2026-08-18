package com.kmarket.navigator.backend.disclosure.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

public record DisclosureCursor(LocalDate filedDate, String receiptNumber) {

	public String encode() {
		String value = filedDate + ":" + receiptNumber;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public static DisclosureCursor decode(String value) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			String[] parts = decoded.split(":", -1);
			if (parts.length != 2 || !parts[1].matches("[0-9]{14}")) {
				throw new IllegalArgumentException();
			}
			return new DisclosureCursor(LocalDate.parse(parts[0]), parts[1]);
		}
		catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new BusinessException(ErrorCode.INVALID_CURSOR);
		}
	}
}
