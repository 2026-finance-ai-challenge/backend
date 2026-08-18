package com.kmarket.navigator.backend.disclosure.presentation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.domain.DocumentStatus;
import com.kmarket.navigator.backend.disclosure.domain.Market;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record DisclosureDetailResponse(
	String receiptNumber,
	String corpCode,
	String issuerNameKo,
	String issuerNameEn,
	String stockCode,
	Market market,
	DisclosureType type,
	String titleKo,
	String titleEn,
	String submitter,
	LocalDate filedDate,
	Instant detectedAt,
	String remark,
	boolean correction,
	DocumentStatus documentStatus,
	String officialUrl,
	List<Document> documents
) {
	static DisclosureDetailResponse from(DisclosureDetail detail, ObjectMapper objectMapper) {
		return new DisclosureDetailResponse(
			detail.receiptNumber(),
			detail.corpCode(),
			detail.issuerNameKo(),
			detail.issuerNameEn(),
			detail.stockCode(),
			detail.market(),
			detail.type(),
			detail.titleKo(),
			detail.titleEn(),
			detail.submitter(),
			detail.filedDate(),
			detail.detectedAt(),
			detail.remark(),
			detail.correction(),
			detail.documentStatus(),
			detail.officialUrl(),
			detail.documents().stream().map(document -> Document.from(document, objectMapper)).toList()
		);
	}

	record Document(UUID id, String sourceFilename, int version, String contentHash, List<Section> sections) {
		private static Document from(DisclosureDocument document, ObjectMapper objectMapper) {
			return new Document(
				 document.id(),
				document.sourceFilename(),
				document.version(),
				document.contentHash(),
				document.sections().stream().map(section -> Section.from(section, objectMapper)).toList()
			);
		}
	}

	record Section(
		UUID id,
		int ordinal,
		SectionKind kind,
		String heading,
		String text,
		JsonNode tableData
	) {
		private static Section from(DisclosureSection section, ObjectMapper objectMapper) {
			JsonNode tableData = section.tableData() == null
				? null
				: objectMapper.readTree(section.tableData());
			return new Section(
				section.id(),
				section.ordinal(),
				section.kind(),
				section.heading(),
				section.text(),
				tableData
			);
		}
	}
}
