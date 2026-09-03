package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStore;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocumentFetch;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSource;
import com.kmarket.navigator.backend.disclosure.application.port.StoredDocumentArchive;

@Component
class FileDocumentArchiveStore implements DocumentArchiveStore {

	private static final int MAX_FOLDER_NAME_LENGTH = 120;
	private static final int MAX_FILENAME_LENGTH = 100;
	private final OpenDartProperties properties;

	FileDocumentArchiveStore(OpenDartProperties properties) {
		this.properties = properties;
	}

	@Override
	public synchronized List<StoredDocumentArchive> store(
		DocumentJob job,
		OpenDartDocumentFetch fetch
	) {
		Path root = properties.archiveRoot().toAbsolutePath().normalize();
		List<StoredDocumentArchive> stored = new ArrayList<>();
		for (OpenDartSource source : fetch.sources()) {
			byte[] archive = source.kind() == DocumentArchiveKind.DART_VIEWER_HTML
				? wrapViewerHtml(job.receiptNumber(), source.content(), source.provenance())
				: source.content();
			String filename = archiveFilename(job.receiptNumber(), source.kind());
			Path folder = root.resolve(stockFolder(job)).normalize();
			Path target = folder.resolve(filename).normalize();
			if (!target.startsWith(root)) {
				throw new IllegalStateException("Archive path escaped configured root");
			}
			writeAtomically(root, folder, target, archive);
			stored.add(new StoredDocumentArchive(
				source.kind(),
				source.status(),
				root.relativize(target).toString(),
				sha256(archive),
				archive.length,
				source.errorCode()
			));
		}
		return List.copyOf(stored);
	}

	private static String stockFolder(DocumentJob job) {
		return sanitize(job.stockCode() + "_" + job.stockNameKo(), MAX_FOLDER_NAME_LENGTH);
	}

	List<StoredDocumentArchive> storeRecovered(String receipt, OpenDartDocumentFetch fetch) {
		if (!receipt.matches("[0-9]{14}")) throw new IllegalArgumentException("Invalid receipt number");
		Path root = properties.archiveRoot().toAbsolutePath().normalize();
		var stored = new ArrayList<StoredDocumentArchive>();
		for (var source : fetch.sources()) {
			byte[] content = source.kind() == DocumentArchiveKind.DART_VIEWER_HTML
				? wrapViewerHtml(receipt, source.content(), source.provenance()) : source.content();
			String hash = sha256(content);
			Path folder = root.resolve("html-repair").resolve(receipt);
			Path target = folder.resolve(hash + "." + source.kind().fileSuffix() + ".zip");
			try {
				Files.createDirectories(folder);
				if (!folder.toRealPath().startsWith(root.toRealPath())) throw new IllegalStateException("Archive path escaped configured root");
				// 복구 원본은 내용 해시 경로에 추가하며 기존 ZIP을 덮어쓰지 않는다.
				try (var channel = java.nio.channels.FileChannel.open(target,
					java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
					java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))) {
					var buffer = java.nio.ByteBuffer.wrap(content);
					while (buffer.hasRemaining()) channel.write(buffer);
					channel.force(true);
				} catch (java.nio.file.FileAlreadyExistsException exists) {
					if (!target.toRealPath().startsWith(root.toRealPath()) || Files.size(target) != content.length
						|| !sha256(Files.readAllBytes(target)).equals(hash)) throw new IllegalStateException("Recovered archive checksum mismatch");
				}
			} catch (IOException error) { throw new IllegalStateException("Failed to persist recovered archive", error); }
			stored.add(new StoredDocumentArchive(source.kind(), source.status(), root.relativize(target).toString(), hash, content.length, source.errorCode()));
		}
		return List.copyOf(stored);
	}

	private static String archiveFilename(String receiptNumber, DocumentArchiveKind kind) {
		return sanitize(receiptNumber + "." + kind.fileSuffix() + ".zip", MAX_FILENAME_LENGTH);
	}

	private static String sanitize(String value, int maximumLength) {
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
			.replaceAll("[\\p{Cntrl}\\/:*?\"<>|]", "_")
			.replaceAll("\\s+", " ")
			.trim();
		if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) {
			throw new IllegalArgumentException("Archive path component is blank");
		}
		return normalized.length() <= maximumLength
			? normalized
			: normalized.substring(0, maximumLength).trim();
	}

	private static void writeAtomically(Path root, Path folder, Path target, byte[] content) {
		try {
			Files.createDirectories(folder);
			Path temporary = root.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
			try {
				Files.write(
					temporary,
					content,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE
				);
				try {
					Files.move(
						temporary,
						target,
						StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING
					);
				}
				catch (AtomicMoveNotSupportedException exception) {
					Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			finally {
				Files.deleteIfExists(temporary);
			}
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to persist OpenDART archive", exception);
		}
	}

	private static byte[] wrapViewerHtml(String receiptNumber, byte[] html, java.util.Map<String, byte[]> provenance) {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (ZipOutputStream zip = new ZipOutputStream(output)) {
				var entry = new ZipEntry(receiptNumber + ".viewer.html");
				entry.setTime(0);
				zip.putNextEntry(entry);
				zip.write(html);
				zip.closeEntry();
				for (var source : new java.util.TreeMap<>(provenance).entrySet()) {
					var raw = new ZipEntry(source.getKey());
					raw.setTime(0);
					zip.putNextEntry(raw);
					zip.write(source.getValue());
					zip.closeEntry();
				}
			}
			return output.toByteArray();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to wrap DART viewer source", exception);
		}
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
