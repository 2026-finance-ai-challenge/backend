package com.kmarket.navigator.backend.disclosure.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSection;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

import tools.jackson.databind.ObjectMapper;

class DisclosurePayloadCodecTests {

	@Test
	void compressesAndRestoresDisclosureSections() {
		DisclosurePayloadCodec codec = new DisclosurePayloadCodec(new ObjectMapper());
		String repeated = "매출액 증가와 해외 수요 확대 ".repeat(1_000);
		OpenDartDocument document = new OpenDartDocument(
			"report.xml",
			"a".repeat(64),
			repeated,
			List.of(new OpenDartSection(
				0,
				SectionKind.TEXT,
				"영업 실적",
				repeated,
				null
			))
		);

		var encoded = codec.encode(document);
		var restored = codec.decode(encoded.compressed());

		assertThat(encoded.compressedBytes()).isLessThan(encoded.originalBytes());
		assertThat(restored).singleElement().satisfies(section -> {
			assertThat(section.ordinal()).isZero();
			assertThat(section.kind()).isEqualTo(SectionKind.TEXT);
			assertThat(section.heading()).isEqualTo("영업 실적");
			assertThat(section.text()).isEqualTo(repeated);
		});
	}
}
