package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kmarket.ai")
public class AiServiceProperties {

	private URI baseUrl = URI.create("http://127.0.0.1:8000");
	private String serviceToken;

	public URI baseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(URI baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String serviceToken() {
		return serviceToken;
	}

	public void setServiceToken(String serviceToken) {
		this.serviceToken = serviceToken;
	}
}
