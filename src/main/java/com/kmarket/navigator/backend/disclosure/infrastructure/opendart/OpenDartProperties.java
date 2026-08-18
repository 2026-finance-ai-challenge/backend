package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "opendart")
public class OpenDartProperties {

	@NotBlank
	@Pattern(regexp = "^[A-Za-z0-9]{40}$")
	private String apiKey;

	public String apiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}
}
