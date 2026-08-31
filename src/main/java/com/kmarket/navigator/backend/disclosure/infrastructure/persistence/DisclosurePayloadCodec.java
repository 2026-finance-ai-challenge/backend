package com.kmarket.navigator.backend.disclosure.infrastructure.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

import tools.jackson.databind.ObjectMapper;

@Component
class DisclosurePayloadCodec {

	private static final int COMPRESSION_LEVEL = 6;
	private static final int MAX_DECOMPRESSED_BYTES = 128 * 1024 * 1024;

	private final ObjectMapper objectMapper;

	DisclosurePayloadCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	EncodedPayload encode(OpenDartDocument document) {
		StoredPayload payload = new StoredPayload(
			document.sanitizedHtml(),
			document.sections().stream()
				.map(section -> new StoredSection(
					UUID.randomUUID(),
					section.ordinal(),
					section.kind(),
					section.heading(),
					section.text(),
					section.tableData()
				))
				.toList()
		);
		byte[] source = objectMapper.writeValueAsBytes(payload);
		byte[] compressed = Zstd.compress(source, COMPRESSION_LEVEL);
		return new EncodedPayload(compressed, source.length, compressed.length);
	}

	List<DisclosureSection> decode(byte[] compressed) {
		return decodePayload(compressed).sections();
	}

	DecodedPayload decodePayload(byte[] compressed) {
		byte[] source;
		try (var input = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
			source = input.readNBytes(MAX_DECOMPRESSED_BYTES + 1);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Disclosure payload decompression failed", exception);
		}
		if (source.length > MAX_DECOMPRESSED_BYTES) {
			throw new IllegalStateException("Disclosure payload exceeds decompression limit");
		}
		StoredPayload payload = objectMapper.readValue(source, StoredPayload.class);
		List<DisclosureSection> sections = payload.sections().stream()
			.map(section -> new DisclosureSection(
				section.id(),
				section.ordinal(),
				section.kind(),
				section.heading(),
				section.text(),
				section.tableData()
			))
			.toList();
		return new DecodedPayload(payload.sanitizedHtml(), sections);
	}

	record DecodedPayload(String sanitizedHtml, List<DisclosureSection> sections) {
		DecodedPayload {
			sections = List.copyOf(sections);
		}
	}

	record EncodedPayload(byte[] compressed, int originalBytes, int compressedBytes) {
		EncodedPayload {
			compressed = compressed.clone();
		}

		@Override
		public byte[] compressed() {
			return compressed.clone();
		}
	}

	private record StoredPayload(String sanitizedHtml, List<StoredSection> sections) {
		private StoredPayload {
			sections = List.copyOf(sections);
		}
	}

	private record StoredSection(
		UUID id,
		int ordinal,
		SectionKind kind,
		String heading,
		String text,
		String tableData
	) {
	}
}
