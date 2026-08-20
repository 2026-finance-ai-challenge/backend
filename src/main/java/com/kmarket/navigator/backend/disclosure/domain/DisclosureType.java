package com.kmarket.navigator.backend.disclosure.domain;

public enum DisclosureType {
	PERIODIC("A"),
	MATERIAL_EVENT("B"),
	ISSUANCE("C"),
	OWNERSHIP("D"),
	OTHER("E"),
	AUDIT("F"),
	FUND("G"),
	SECURITIZATION("H"),
	EXCHANGE("I"),
	FAIR_TRADE("J");

	private final String code;

	DisclosureType(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public static DisclosureType fromCode(String code) {
		for (DisclosureType value : values()) {
			if (value.code.equals(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unsupported disclosure type");
	}
}
