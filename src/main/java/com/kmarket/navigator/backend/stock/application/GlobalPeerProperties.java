package com.kmarket.navigator.backend.stock.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kmarket.global-peer")
public class GlobalPeerProperties {

	private String dataVersion = "kmarket-global-peer-2026-09-01-openai-v2";

	public String dataVersion() {
		return dataVersion;
	}

	public void setDataVersion(String dataVersion) {
		this.dataVersion = dataVersion;
	}
}
