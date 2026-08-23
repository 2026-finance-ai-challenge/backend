package com.kmarket.navigator.backend.tax.infrastructure.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kmarket.tax.documents")
public class TaxDocumentProperties {

	private Path root = Path.of("./data/tax-documents");
	private String encryptionKeyBase64;
	private long maxFileSizeBytes = 10 * 1024 * 1024;
	private Duration retentionAfterDelete = Duration.ofDays(30);

	public Path root() {
		return root.toAbsolutePath().normalize();
	}

	public void setRoot(Path root) {
		this.root = root;
	}

	public void setEncryptionKeyBase64(String encryptionKeyBase64) {
		this.encryptionKeyBase64 = encryptionKeyBase64;
	}

	public long maxFileSizeBytes() {
		return maxFileSizeBytes;
	}

	public void setMaxFileSizeBytes(long maxFileSizeBytes) {
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	public Duration retentionAfterDelete() {
		return retentionAfterDelete;
	}

	public void setRetentionAfterDelete(Duration retentionAfterDelete) {
		this.retentionAfterDelete = retentionAfterDelete;
	}

	public SecretKey encryptionKey() {
		if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
			throw new IllegalStateException("Tax document encryption key is required");
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(encryptionKeyBase64);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Tax document encryption key is invalid", exception);
		}
		if (decoded.length != 32) {
			throw new IllegalStateException("Tax document encryption key must be 32 bytes");
		}
		return new SecretKeySpec(decoded, "HmacSHA256");
	}
}
