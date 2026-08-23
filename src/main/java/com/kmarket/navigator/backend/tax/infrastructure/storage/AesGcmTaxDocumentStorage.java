package com.kmarket.navigator.backend.tax.infrastructure.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentStorage;

@Component
public class AesGcmTaxDocumentStorage implements TaxDocumentStorage {

	private static final byte[] HEADER = "KMTD1".getBytes(StandardCharsets.US_ASCII);
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
		PosixFilePermission.OWNER_READ,
		PosixFilePermission.OWNER_WRITE,
		PosixFilePermission.OWNER_EXECUTE
	);
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
		PosixFilePermission.OWNER_READ,
		PosixFilePermission.OWNER_WRITE
	);
	private final TaxDocumentProperties properties;
	private final SecureRandom secureRandom = new SecureRandom();

	public AesGcmTaxDocumentStorage(TaxDocumentProperties properties) {
		this.properties = properties;
	}

	@Override
	public String store(
		UUID userId,
		UUID documentId,
		String sha256,
		String mediaType,
		byte[] content
	) {
		String storageKey = userId + "/" + documentId + ".bin";
		Path destination = safePath(storageKey);
		Path temporary = null;
		try {
			createSecureDirectory(destination.getParent());
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination)) {
				throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
			}
			byte[] encrypted = encrypt(userId, documentId, sha256, mediaType, content);
			temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
			setPermissions(temporary, FILE_PERMISSIONS);
			Files.write(temporary, encrypted, StandardOpenOption.TRUNCATE_EXISTING);
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, destination);
			}
			setPermissions(destination, FILE_PERMISSIONS);
			return storageKey;
		}
		catch (BusinessException exception) {
			deleteTemporary(temporary);
			throw exception;
		}
		catch (IOException | GeneralSecurityException exception) {
			deleteTemporary(temporary);
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
	}

	@Override
	public byte[] read(
		UUID userId,
		UUID documentId,
		String sha256,
		String mediaType,
		String storageKey
	) {
		Path source = safePath(storageKey);
		if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
		try {
			byte[] payload = Files.readAllBytes(source);
			return decrypt(userId, documentId, sha256, mediaType, payload);
		}
		catch (IOException | GeneralSecurityException exception) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
	}

	@Override
	public void delete(String storageKey) {
		Path target = safePath(storageKey);
		if (Files.isSymbolicLink(target)) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
		try {
			Files.deleteIfExists(target);
		}
		catch (IOException exception) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
	}

	private byte[] encrypt(
		UUID userId,
		UUID documentId,
		String sha256,
		String mediaType,
		byte[] content
	) throws GeneralSecurityException {
		byte[] nonce = new byte[NONCE_BYTES];
		secureRandom.nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, documentKey(userId, documentId), new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData(userId, documentId, sha256, mediaType));
		byte[] cipherText = cipher.doFinal(content);
		return ByteBuffer.allocate(HEADER.length + nonce.length + cipherText.length)
			.put(HEADER)
			.put(nonce)
			.put(cipherText)
			.array();
	}

	private byte[] decrypt(
		UUID userId,
		UUID documentId,
		String sha256,
		String mediaType,
		byte[] payload
	) throws GeneralSecurityException {
		if (payload.length <= HEADER.length + NONCE_BYTES
			|| !Arrays.equals(HEADER, Arrays.copyOfRange(payload, 0, HEADER.length))) {
			throw new GeneralSecurityException("Invalid encrypted document header");
		}
		byte[] nonce = Arrays.copyOfRange(payload, HEADER.length, HEADER.length + NONCE_BYTES);
		byte[] cipherText = Arrays.copyOfRange(payload, HEADER.length + NONCE_BYTES, payload.length);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, documentKey(userId, documentId), new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData(userId, documentId, sha256, mediaType));
		return cipher.doFinal(cipherText);
	}

	private SecretKey documentKey(UUID userId, UUID documentId) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(properties.encryptionKey());
		byte[] derived = mac.doFinal(("kmarket-tax-document:" + userId + ":" + documentId)
			.getBytes(StandardCharsets.UTF_8));
		return new SecretKeySpec(derived, "AES");
	}

	private byte[] associatedData(UUID userId, UUID documentId, String sha256, String mediaType) {
		return (userId + ":" + documentId + ":" + sha256 + ":" + mediaType)
			.getBytes(StandardCharsets.UTF_8);
	}

	private Path safePath(String storageKey) {
		Path relative;
		try {
			relative = Path.of(storageKey);
		}
		catch (RuntimeException exception) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
		if (relative.isAbsolute() || relative.getNameCount() != 2
			|| !relative.getName(0).toString().matches("[0-9a-f-]{36}")
			|| !relative.getName(1).toString().matches("[0-9a-f-]{36}\\.bin")) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
		Path root = properties.root();
		Path resolved = root.resolve(relative).normalize();
		if (!resolved.startsWith(root)) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STORAGE_UNAVAILABLE);
		}
		return resolved;
	}

	private void createSecureDirectory(Path directory) throws IOException {
		Path root = properties.root();
		Files.createDirectories(root);
		if (Files.isSymbolicLink(root)) {
			throw new IOException("Storage root must not be a symbolic link");
		}
		setPermissions(root, DIRECTORY_PERMISSIONS);
		Files.createDirectories(directory);
		if (Files.isSymbolicLink(directory)) {
			throw new IOException("Storage directory must not be a symbolic link");
		}
		setPermissions(directory, DIRECTORY_PERMISSIONS);
	}

	private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(path, permissions);
		}
		catch (UnsupportedOperationException ignored) {
			// POSIX 권한을 지원하지 않는 개발 환경에서는 애플리케이션 권한으로 격리한다.
		}
	}

	private void deleteTemporary(Path temporary) {
		if (temporary == null) {
			return;
		}
		try {
			Files.deleteIfExists(temporary);
		}
		catch (IOException ignored) {
			// 임시 파일은 무작위 이름이며 다음 보안 점검에서 정리 대상으로 탐지된다.
		}
	}
}
