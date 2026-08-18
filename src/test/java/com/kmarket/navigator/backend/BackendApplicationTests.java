package com.kmarket.navigator.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartFiling;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSection;
import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureCursor;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

@Testcontainers
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@SpringBootTest(properties = "opendart.api-key=0000000000000000000000000000000000000000")
class BackendApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES =
		new PostgreSQLContainer(
			DockerImageName.parse("pgvector/pgvector:0.8.6-pg18-trixie")
				.asCompatibleSubstituteFor("postgres")
		);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DisclosureRepository disclosureRepository;

	@Autowired
	DisclosureQueryHandler disclosureQueryHandler;

	@Autowired
	JdbcClient jdbcClient;

	@MockitoBean
	DisclosureRagGateway disclosureRagGateway;

	@Test
	void contextLoads() {
	}

	@Test
	void healthEndpointIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void disclosureListIsPublic() throws Exception {
		mockMvc.perform(get("/api/v1/disclosures"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void writeApiIsDeniedByDefault() throws Exception {
		mockMvc.perform(post("/api/v1/disclosures"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/disclosures/not-a-receipt"))
			.andExpect(status().isForbidden());
	}

	@Test
	void rejectsInvalidListParameters() throws Exception {
		mockMvc.perform(get("/api/v1/disclosures").param("stockCode", "123"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mockMvc.perform(get("/api/v1/disclosures")
				.param("from", "2026-08-18")
				.param("to", "2026-08-17"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
		mockMvc.perform(get("/api/v1/disclosures").param("cursor", "invalid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}

	@Test
	void storesFilingsIdempotentlyAndKeepsDocumentVersions() throws Exception {
		OpenDartFiling filing = filing("20260818800670");

		assertThat(disclosureRepository.saveFiling(filing)).isTrue();
		assertThat(disclosureRepository.saveFiling(filing)).isFalse();
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(document("a", "first")));
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(document("b", "second")));

		var detail = disclosureRepository.findByReceiptNumber(filing.receiptNumber()).orElseThrow();
		assertThat(detail.documents()).singleElement().satisfies(document -> {
			assertThat(document.version()).isEqualTo(2);
			assertThat(document.sections()).singleElement()
				.extracting(section -> section.text())
				.isEqualTo("second");
		});
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM disclosure_document")
			.query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbcClient.sql("""
			SELECT status FROM ingestion_job
			WHERE job_type = 'DISCLOSURE_EMBEDDING' AND business_key = :receiptNumber
			""")
			.param("receiptNumber", filing.receiptNumber())
			.query(String.class)
			.single()).isEqualTo("PENDING");

		mockMvc.perform(get("/api/v1/disclosures/{receiptNumber}", filing.receiptNumber()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.receiptNumber").value(filing.receiptNumber()))
			.andExpect(jsonPath("$.documents[0].version").value(2))
			.andExpect(jsonPath("$.documents[0].sections[0].text").value("second"));
	}

	@Test
	void filtersAndPaginatesDisclosureList() {
		disclosureRepository.saveFiling(filing("20260818800670"));
		disclosureRepository.saveFiling(filing("20260818800671"));

		var first = disclosureQueryHandler.findAll(
			new DisclosureListQuery("005930", LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18),
				Set.of(DisclosureType.MATERIAL_EVENT), null, 1)
		);
		assertThat(first.items())
			.extracting(item -> item.receiptNumber())
			.containsExactly("20260818800671");
		assertThat(first.nextCursor()).isNotBlank();

		var second = disclosureQueryHandler.findAll(
			new DisclosureListQuery("005930", LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18),
				Set.of(DisclosureType.MATERIAL_EVENT), DisclosureCursor.decode(first.nextCursor()), 1)
		);
		assertThat(second.items())
			.extracting(item -> item.receiptNumber())
			.containsExactly("20260818800670");
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void asksQuestionOnlyAfterDisclosureIndexIsReady() throws Exception {
		OpenDartFiling filing = filing("20260818800670");
		disclosureRepository.saveFiling(filing);
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(document("a", "first")));

		mockMvc.perform(post("/api/v1/disclosures/{receiptNumber}/questions", filing.receiptNumber())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"What changed?\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DISCLOSURE_INDEX_NOT_READY"));

		jdbcClient.sql("UPDATE disclosure SET index_status = 'READY' WHERE receipt_number = :receiptNumber")
			.param("receiptNumber", filing.receiptNumber())
			.update();
		when(disclosureRagGateway.ask(eq(filing.receiptNumber()), any()))
			.thenReturn(new DisclosureAnswer(
				"Revenue increased. [C1]",
				false,
				null,
				List.of(),
				"test-model",
				"test-prompt"
			));

		mockMvc.perform(post("/api/v1/disclosures/{receiptNumber}/questions", filing.receiptNumber())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"What changed?\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answer").value("Revenue increased. [C1]"))
			.andExpect(jsonPath("$.model").value("test-model"));
	}

	private static OpenDartFiling filing(String receiptNumber) {
		return new OpenDartFiling(
			receiptNumber,
			"00126380",
			"삼성전자",
			"005930",
			CorporationClass.KOSPI,
			DisclosureType.MATERIAL_EVENT,
			"기업설명회(IR) 개최",
			"삼성전자",
			LocalDate.of(2026, 8, 18),
			""
		);
	}

	private static OpenDartDocument document(String hashCharacter, String text) {
		return new OpenDartDocument(
			"filing.xml",
			hashCharacter.repeat(64),
			text,
			List.of(new OpenDartSection(0, SectionKind.TEXT, null, text, null))
		);
	}
}
