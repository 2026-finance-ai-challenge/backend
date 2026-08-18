package com.kmarket.navigator.backend.disclosure.domain;

public enum CorporationClass {
	KOSPI("Y"),
	KOSDAQ("K"),
	KONEX("N"),
	OTHER("E");

	private final String code;

	CorporationClass(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public Market market() {
		return switch (this) {
			case KOSPI -> Market.KOSPI;
			case KOSDAQ -> Market.KOSDAQ;
			case KONEX -> Market.KONEX;
			case OTHER -> Market.OTHER;
		};
	}

	public static CorporationClass fromCode(String code) {
		for (CorporationClass value : values()) {
			if (value.code.equals(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unsupported corporation class");
	}
}
