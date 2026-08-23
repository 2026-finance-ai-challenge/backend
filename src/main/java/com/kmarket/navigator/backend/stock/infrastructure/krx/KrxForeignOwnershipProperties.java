package com.kmarket.navigator.backend.stock.infrastructure.krx;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kmarket.market.krx")
public class KrxForeignOwnershipProperties {

	private boolean enabled;
	private URI baseUrl = URI.create("https://data.krx.co.kr");
	private String loginPath = "/contents/MDC/COMS/client/MDCCOMS001D1.cmd";
	private String memberId = "";
	private String password = "";
	private String historyBld = "dbms/MDC/STAT/standard/MDCSTAT03702";
	private Duration connectTimeout = Duration.ofSeconds(3);
	private Duration readTimeout = Duration.ofSeconds(10);

	public boolean configured() {
		return enabled && !memberId.isBlank() && !password.isBlank();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public URI getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(URI baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getLoginPath() {
		return loginPath;
	}

	public void setLoginPath(String loginPath) {
		this.loginPath = loginPath;
	}

	public String getMemberId() {
		return memberId;
	}

	public void setMemberId(String memberId) {
		this.memberId = memberId;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getHistoryBld() {
		return historyBld;
	}

	public void setHistoryBld(String historyBld) {
		this.historyBld = historyBld;
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(Duration readTimeout) {
		this.readTimeout = readTimeout;
	}
}
