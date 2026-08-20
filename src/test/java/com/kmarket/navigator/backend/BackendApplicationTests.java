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
import java.util.UUID;

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

import com.kmarket.navigator.backend.disclosure.application.DisclosureDocumentHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureBackfillRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartFiling;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSection;
import com.kmarket.navigator.backend.disclosure.application.port.StoredDocumentArchive;
import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureCursor;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;
import com.kmarket.navigator.backend.disclosure.domain.Market;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

@Testcontainers
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@SpringBootTest(properties = "opendart.api-keys=0000000000000000000000000000000000000000")
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
	DisclosureBackfillRepository disclosureBackfillRepository;

	@Autowired
	DisclosureQueryHandler disclosureQueryHandler;

	@Autowired
	JdbcClient jdbcClient;

	@MockitoBean
	DisclosureRagGateway disclosureRagGateway;

	@MockitoBean
	DisclosureDocumentHandler disclosureDocumentHandler;

	@Test
	void contextLoads() {
	}

	@Test
	void serviceStockUniverseContainsMvpStocks() {
		assertThat(jdbcClient.sql("SELECT stock_code FROM service_stock_universe ORDER BY stock_code")
			.query(String.class)
			.list())
			.hasSize(75)
			.contains("005930", "0126Z0", "015760", "017670")
			.doesNotContain(
				"020560", "030200", "031310", "033130", "033830", "034120",
				"035760", "036030", "036420", "036460", "036630", "037560",
				"039290", "039340", "040300", "053210", "058400", "065530",
				"066790", "089590", "091810", "122450", "126560", "127710",
				"272450", "298690");
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
	void acceptsKrxAlphanumericCommonStockCode() throws Exception {
		OpenDartFiling filing = new OpenDartFiling(
			"20260818000213",
			"01999999",
			"삼성에피스홀딩스",
			"0126Z0",
			CorporationClass.KOSDAQ,
			DisclosureType.OWNERSHIP,
			"임원ㆍ주요주주특정증권등소유상황보고서",
			"삼성에피스홀딩스",
			LocalDate.of(2026, 8, 18),
			""
		);
		assertThat(disclosureRepository.saveFiling(filing)).isTrue();
		activateCommonStocks("0126Z0");

		mockMvc.perform(get("/api/v1/disclosures").param("stockCode", "0126Z0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].stockCode").value("0126Z0"));
	}

	@Test
	void promotesOldDisclosureToOnDemandIndexing() throws Exception {
		OpenDartFiling oldFiling = new OpenDartFiling(
			"20100101000001",
			"00126380",
			"삼성전자",
			"005930",
			CorporationClass.KOSPI,
			DisclosureType.PERIODIC,
			"사업보고서",
			"삼성전자",
			LocalDate.of(2010, 1, 1),
			""
		);
		disclosureRepository.saveFiling(oldFiling);
		activateCommonStocks("005930");

		mockMvc.perform(post(
			"/api/v1/disclosures/{receiptNumber}/index",
			oldFiling.receiptNumber()
		)).andExpect(status().isAccepted());

		assertThat(jdbcClient.sql("""
			SELECT last_error_code || ':' || priority
			FROM ingestion_job
			WHERE job_type = 'DISCLOSURE_DOCUMENT' AND business_key = :receiptNumber
			""")
			.param("receiptNumber", oldFiling.receiptNumber())
			.query(String.class)
			.single()).isEqualTo("ON_DEMAND:0");
	}

	@Test
	void claimsRagTargetBeforeHistoricalArchive() {
		OpenDartFiling historical = filingAt("20100101000002", LocalDate.of(2010, 1, 1));
		LocalDate databaseToday = jdbcClient.sql("SELECT CURRENT_DATE")
			.query(LocalDate.class)
			.single();
		OpenDartFiling recent = filingAt("20260819000002", databaseToday.minusDays(1));
		disclosureRepository.saveFiling(historical);
		disclosureRepository.saveFiling(recent);
		activateCommonStocks("005930");

		var claimed = disclosureRepository.claimDocumentJob("priority-test").orElseThrow();

		assertThat(claimed.receiptNumber()).isEqualTo(recent.receiptNumber());
	}

	@Test
	void doesNotCreateIngestionJobsOutsideServiceStockUniverse() {
		OpenDartFiling unsupported = new OpenDartFiling(
			"20260818800999",
			"00999999",
			"지원대상외기업",
			"999999",
			CorporationClass.KOSPI,
			DisclosureType.MATERIAL_EVENT,
			"기업설명회(IR) 개최",
			"지원대상외기업",
			LocalDate.of(2026, 8, 18),
			""
		);

		disclosureRepository.saveFiling(unsupported);

		assertThat(jdbcClient.sql("""
			SELECT COUNT(*)
			FROM ingestion_job
			WHERE business_key = :receiptNumber
			  AND job_type IN (
			      'DISCLOSURE_DOCUMENT',
			      'DISCLOSURE_EMBEDDING',
			      'DISCLOSURE_METADATA_EMBEDDING'
			  )
			""")
			.param("receiptNumber", unsupported.receiptNumber())
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void keepsAuthoritativeMarketForCommonStockWhenDartClassDiffers() {
		OpenDartFiling listedFiling = filing("20260818800672");
		disclosureRepository.saveFiling(listedFiling);
		activateCommonStocks("005930");
		OpenDartFiling otherClassFiling = new OpenDartFiling(
			"20260818800673",
			"00126380",
			"삼성전자",
			"005930",
			CorporationClass.OTHER,
			DisclosureType.MATERIAL_EVENT,
			"기업설명회(IR) 개최",
			"삼성전자",
			LocalDate.of(2026, 8, 18),
			""
		);

		disclosureRepository.saveFiling(otherClassFiling);

		assertThat(jdbcClient.sql("SELECT market FROM security WHERE stock_code = '005930'")
			.query(String.class)
			.single()).isEqualTo("KOSPI");
	}

	@Test
	void resumesBackfillFromPersistedCheckpoint() {
		LocalDate from = LocalDate.of(2026, 8, 16);
		LocalDate to = LocalDate.of(2026, 8, 18);
		var firstRun = UUID.randomUUID();
		var claimed = disclosureBackfillRepository.startOrResume(from, to, firstRun);
		disclosureBackfillRepository.advance(claimed.id(), firstRun, from, from, 3);
		disclosureBackfillRepository.fail(claimed.id(), firstRun, "NETWORK_ERROR");

		var secondRun = UUID.randomUUID();
		var resumed = disclosureBackfillRepository.startOrResume(from, to, secondRun);

		assertThat(resumed.nextDate()).isEqualTo(from.plusDays(1));
		assertThat(resumed.collectedCount()).isEqualTo(3);
		assertThat(resumed.runId()).isEqualTo(secondRun);
	}

	@Test
	void storesFilingsIdempotentlyAndKeepsDocumentVersions() throws Exception {
		OpenDartFiling filing = filing("20260818800670");

		assertThat(disclosureRepository.saveFiling(filing)).isTrue();
		assertThat(disclosureRepository.saveFiling(filing)).isFalse();
		activateCommonStocks("005930");
		disclosureRepository.completeDocumentJob(
			filing.receiptNumber(),
			List.of(document("a", "first")),
			List.of(new StoredDocumentArchive(
				DocumentArchiveKind.OPENDART_ZIP,
				DocumentArchiveStatus.VERIFIED,
				"005930_삼성전자/20260818800670.api.zip",
				"a".repeat(64),
				3,
				null
			))
		);
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(document("b", "second")), List.of());

		var detail = disclosureRepository.findByReceiptNumber(filing.receiptNumber()).orElseThrow();
		assertThat(detail.documents()).singleElement().satisfies(document -> {
			assertThat(document.version()).isEqualTo(2);
			assertThat(document.sections()).singleElement()
				.extracting(section -> section.text())
				.isEqualTo("second");
		});
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM disclosure_document")
			.query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbcClient.sql("SELECT relative_path FROM disclosure_archive WHERE receipt_number = :receiptNumber")
			.param("receiptNumber", filing.receiptNumber())
			.query(String.class).single())
			.isEqualTo("005930_삼성전자/20260818800670.api.zip");
		assertThat(jdbcClient.sql("""
			SELECT parser_version
			FROM disclosure_document
			WHERE disclosure_id = (
			    SELECT id FROM disclosure WHERE receipt_number = :receiptNumber
			)
			  AND is_current
			""")
			.param("receiptNumber", filing.receiptNumber())
			.query(String.class)
			.single()).isEqualTo("opendart-html-v3");
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
		activateCommonStocks("005930");

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
		activateCommonStocks("005930");
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(document("a", "first")), List.of());

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
		return filingAt(receiptNumber, LocalDate.of(2026, 8, 18));
	}

	private static OpenDartFiling filingAt(String receiptNumber, LocalDate filedDate) {
		return new OpenDartFiling(
			receiptNumber,
			"00126380",
			"삼성전자",
			"005930",
			CorporationClass.KOSPI,
			DisclosureType.MATERIAL_EVENT,
			"기업설명회(IR) 개최",
			"삼성전자",
			filedDate,
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

	private void activateCommonStocks(String... stockCodes) {
		disclosureRepository.replaceCommonStockUniverse(
			java.util.Arrays.stream(stockCodes)
				.map(stockCode -> new ListedCommonStock(stockCode, stockCode, Market.KOSPI))
				.toList()
		);
	}
}
