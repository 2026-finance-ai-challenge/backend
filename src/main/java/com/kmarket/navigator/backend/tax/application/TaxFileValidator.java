package com.kmarket.navigator.backend.tax.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.tax.infrastructure.storage.TaxDocumentProperties;

@Component
public class TaxFileValidator {

	private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] PNG = new byte[] {
		(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
	};
	private static final Set<String> ACTIVE_PDF_TOKENS = Set.of(
		"/javascript", "/openaction", "/launch", "/embeddedfile", "/richmedia", "/xfa", "/encrypt"
	);
	private static final Map<String, Set<String>> EXTENSIONS = Map.of(
		"application/pdf", Set.of(".pdf"),
		"image/jpeg", Set.of(".jpg", ".jpeg"),
		"image/png", Set.of(".png")
	);
	private final TaxDocumentProperties properties;

	public TaxFileValidator(TaxDocumentProperties properties) {
		this.properties = properties;
	}

	public ValidatedTaxFile validate(MultipartFile file) {
		if (file == null || file.isEmpty() || file.getSize() > properties.maxFileSizeBytes()) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException exception) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
		if (content.length == 0 || content.length > properties.maxFileSizeBytes()) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		String fileName = safeFileName(file.getOriginalFilename());
		String mediaType = detect(content);
		String extension = extension(fileName);
		if (!EXTENSIONS.get(mediaType).contains(extension)) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		String declared = file.getContentType();
		if (declared != null && !declared.isBlank()
			&& !"application/octet-stream".equalsIgnoreCase(declared)
			&& !mediaType.equalsIgnoreCase(declared)) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		return new ValidatedTaxFile(fileName, mediaType, content, sha256(content));
	}

	private String detect(byte[] content) {
		if (startsWith(content, PDF) && validPdf(content)) {
			return "application/pdf";
		}
		if (startsWith(content, PNG) && validPng(content)) {
			return "image/png";
		}
		if (validJpeg(content)) {
			return "image/jpeg";
		}
		throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
	}

	private boolean validPdf(byte[] content) {
		int tailStart = Math.max(0, content.length - 2048);
		String tail = new String(content, tailStart, content.length - tailStart, StandardCharsets.ISO_8859_1);
		if (!tail.contains("%%EOF")) {
			return false;
		}
		String document = new String(content, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
		return ACTIVE_PDF_TOKENS.stream().noneMatch(document::contains);
	}

	private boolean validPng(byte[] content) {
		return content.length >= 20
			&& content[content.length - 8] == 0x49
			&& content[content.length - 7] == 0x45
			&& content[content.length - 6] == 0x4e
			&& content[content.length - 5] == 0x44;
	}

	private boolean validJpeg(byte[] content) {
		return content.length >= 4
			&& (content[0] & 0xff) == 0xff
			&& (content[1] & 0xff) == 0xd8
			&& (content[2] & 0xff) == 0xff
			&& (content[content.length - 2] & 0xff) == 0xff
			&& (content[content.length - 1] & 0xff) == 0xd9;
	}

	private boolean startsWith(byte[] content, byte[] signature) {
		if (content.length < signature.length) {
			return false;
		}
		for (int index = 0; index < signature.length; index++) {
			if (content[index] != signature[index]) {
				return false;
			}
		}
		return true;
	}

	private String safeFileName(String original) {
		String value = original == null ? "document" : original.replace('\\', '/');
		value = value.substring(value.lastIndexOf('/') + 1).strip();
		if (value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		return value;
	}

	private String extension(String fileName) {
		int index = fileName.lastIndexOf('.');
		return index < 0 ? "" : fileName.substring(index).toLowerCase(Locale.ROOT);
	}

	private String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
