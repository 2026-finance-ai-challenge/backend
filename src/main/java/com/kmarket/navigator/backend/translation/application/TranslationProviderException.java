package com.kmarket.navigator.backend.translation.application;

public final class TranslationProviderException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final Failure failure;

	public TranslationProviderException(Failure failure) {
		super(failure.code());
		this.failure = failure;
	}

	public Failure failure() {
		return failure;
	}

	public enum Failure {
		INVALID_OUTPUT("AI_INVALID_OUTPUT"),
		QUOTA_EXHAUSTED("AI_PROVIDER_QUOTA_EXHAUSTED"),
		RATE_LIMITED("AI_PROVIDER_RATE_LIMITED"),
		TIMEOUT("AI_PROVIDER_TIMEOUT"),
		UNAVAILABLE("AI_PROVIDER_UNAVAILABLE");

		private final String code;

		Failure(String code) {
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
