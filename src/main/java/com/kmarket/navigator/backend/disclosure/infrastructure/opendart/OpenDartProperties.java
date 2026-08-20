package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

	@Validated
@ConfigurationProperties(prefix = "opendart")
public class OpenDartProperties {

	@NotEmpty
	private List<@NotBlank @Pattern(regexp = "^[A-Za-z0-9]{40}$") String> apiKeys = new ArrayList<>();

	private Path archiveRoot = Path.of("data/opendart-archives");

	public List<String> apiKeys() {
		return List.copyOf(apiKeys);
	}

	public String apiKey() {
		return apiKeys.get(0);
	}

	public void setApiKeys(List<String> apiKeys) {
		this.apiKeys = apiKeys == null ? new ArrayList<>() : new ArrayList<>(apiKeys);
	}

	public void setApiKey(String apiKey) {
		setApiKeys(apiKey == null ? List.of() : List.of(apiKey));
	}

	public Path archiveRoot() {
		return archiveRoot;
	}

	public void setArchiveRoot(Path archiveRoot) {
		this.archiveRoot = archiveRoot;
	}
}
