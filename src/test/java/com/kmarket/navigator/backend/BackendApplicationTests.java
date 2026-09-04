package com.kmarket.navigator.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.kmarket.navigator.backend.disclosure.application.DisclosureDocumentHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureTitleTranslationWorker;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureBackfillRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureInsightGateway;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartFiling;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartCorporation;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSection;
import com.kmarket.navigator.backend.disclosure.application.port.StoredDocumentArchive;
import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureCursor;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightGeneration;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;
import com.kmarket.navigator.backend.disclosure.domain.Market;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;
import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.disclosure.infrastructure.opendart.OpenDartProperties;
import com.kmarket.navigator.backend.news.application.NewsClusterReconciliationService;
import com.kmarket.navigator.backend.news.application.port.NewsAiGateway;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.TermExplanation;
import com.kmarket.navigator.backend.news.domain.TermReference;
import com.kmarket.navigator.backend.news.domain.NewsRetention;
import com.kmarket.navigator.backend.news.infrastructure.naver.NaverNewsProperties;
import com.kmarket.navigator.backend.chat.application.ChatGenerationWorker;
import com.kmarket.navigator.backend.identity.application.port.AuthSessionRepository;
import com.kmarket.navigator.backend.identity.domain.AuthSession;
import com.kmarket.navigator.backend.chat.application.port.AgentGateway;
import com.kmarket.navigator.backend.chat.domain.AgentAnswer;
import com.kmarket.navigator.backend.tax.application.TaxDocumentWorker;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentGateway;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentIssue;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification;
import com.kmarket.navigator.backend.tax.infrastructure.storage.TaxDocumentProperties;
import com.kmarket.navigator.backend.stock.application.port.GlobalPeerGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.GlobalPeerAnalysis;
import com.kmarket.navigator.backend.translation.application.TranslationWorker;
import com.kmarket.navigator.backend.translation.application.port.TranslationAiGateway;
import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@SpringBootTest(properties = {
	"opendart.api-keys=0000000000000000000000000000000000000000",
	"kmarket.cors.allowed-origins=https://kartkr.cloud,http://localhost:5173",
	"kmarket.tax.documents.encryption-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class BackendApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES =
		new PostgreSQLContainer(
			DockerImageName.parse("pgvector/pgvector:0.8.6-pg18-trixie")
				.asCompatibleSubstituteFor("postgres")
		);

	@Container
	@ServiceConnection(name = "redis")
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.6.2"))
		.withExposedPorts(6379);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DisclosureRepository disclosureRepository;

	@Autowired
	DisclosureBackfillRepository disclosureBackfillRepository;

	@Autowired
	DisclosureQueryHandler disclosureQueryHandler;

	@Autowired
	DisclosureTitleTranslationWorker disclosureTitleTranslationWorker;

	@Autowired
	NewsRepository newsRepository;

	@Autowired
	MarketSnapshotRepository marketSnapshotRepository;

	@Autowired
	NewsClusterReconciliationService newsClusterReconciliationService;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	AuthSessionRepository authSessions;

	@Test
	void browserRefreshReplayIsBoundToRequestContextAndShortRecoveryWindow() {
		for (String scenario : List.of("same", "expired", "different-request", "different-context")) {
			Instant now = Instant.now();
			UUID userId = UUID.randomUUID();
			UUID familyId = UUID.randomUUID();
			AuthSession original = authSession(userId, familyId, UUID.randomUUID().toString(), "ip", now.minusSeconds(180));
			authSessions.insert(original);
			String successorHash = UUID.randomUUID().toString();
			AuthSession successor = authSession(userId, familyId, successorHash, "ip",
				scenario.equals("expired") ? now.minusSeconds(121) : now);
			assertThat(authSessions.rotate(original, successor)).isEqualTo(AuthSessionRepository.RotationResult.ROTATED);
			AuthSession duplicate = authSession(userId, familyId,
				scenario.equals("different-request") ? UUID.randomUUID().toString() : successorHash,
				scenario.equals("different-context") ? "other-ip" : "ip", now);
			assertThat(authSessions.rotate(original, duplicate)).as(scenario).isEqualTo(scenario.equals("same")
				? AuthSessionRepository.RotationResult.REPLAYED : AuthSessionRepository.RotationResult.REUSED);
			assertThat(authSessions.findActiveById(successor.id()).isPresent()).isEqualTo(scenario.equals("same"));
		}
	}

	private AuthSession authSession(UUID userId, UUID familyId, String tokenHash, String ip, Instant issuedAt) {
		return new AuthSession(UUID.randomUUID(), familyId, userId, tokenHash, ip, "agent", issuedAt,
			issuedAt.plusSeconds(900), issuedAt.plusSeconds(86400), "ACTIVE", null);
	}

	@Test
	void databaseEnglishTitleChecksMatchCurrencyAndPersonNamePolicy() {
		List<String> titles = List.of("Jo discusses earnings", "Samjeonnix rallies", "Sales of KRW 3 trillion",
			"Sales of 3 jo", "Sales of 3jo", "Sales of 3.2 eok", "Sales of eok won", "Sales of man-won",
			"삼성 earnings", "東京 earnings", "㐀 earnings");
		for (String table : List.of("translation_memory", "news_article")) {
			String check = jdbcClient.sql("SELECT pg_get_expr(conbin, conrelid) FROM pg_constraint WHERE conname=:name")
				.param("name", table + "_english_title_script").query(String.class).single();
			for (String title : titles) {
				boolean accepted = jdbcClient.sql("SELECT " + check + " FROM (SELECT CAST(:title AS text) AS translated_text, "
					+ "CAST(:title AS text) AS english_title, 'NEWS_TITLE' AS content_kind, 'en' AS target_locale, 'READY' AS status) value")
					.param("title", title).query(Boolean.class).single();
				assertThat(accepted).as("%s: %s", table, title)
					.isEqualTo(com.kmarket.navigator.backend.global.text.EnglishTextPolicy.isValid(title));
			}
		}
	}

	@Test
	void titleTransportFailuresHaveBoundedBackoffButInvalidOutputsDoNotRetry() {
		for (String code : List.of("AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE",
			"AI_PROVIDER_RATE_LIMITED", "AI_INVALID_OUTPUT", "AI_GENERATION_INCOMPLETE")) {
			UUID id = UUID.randomUUID();
			Instant now = Instant.now();
			jdbcClient.sql("""
				INSERT INTO translation_memory (id,content_kind,source_locale,target_locale,translation_version,
				 source_hash,source_text,normalized_source_text,status,created_at,updated_at)
				VALUES (:id,'NEWS_TITLE','ko','en','news-title-v3',:hash,'테스트','테스트','PROCESSING',now(),now())
				""").param("id", id).param("hash", id.toString().replace("-", "").repeat(2)).update();
			jdbcClient.sql("""
				INSERT INTO translation_job (translation_memory_id,status,attempts,available_at,created_at,updated_at)
				VALUES (:id,'PROCESSING',1,now(),now(),now())
				""").param("id", id).update();
			translationRepository.fail(id, 1, code, now, Duration.ofSeconds(15));
			String expected = code.startsWith("AI_PROVIDER_") ? "PENDING" : "FAILED";
			for (String query : List.of("SELECT status FROM translation_job WHERE translation_memory_id=:id",
				"SELECT status FROM translation_memory WHERE id=:id")) {
				assertThat(jdbcClient.sql(query).param("id", id).query(String.class).single()).isEqualTo(expected);
			}
			jdbcClient.sql("UPDATE translation_job SET status='PROCESSING',attempts=3 WHERE translation_memory_id=:id")
				.param("id", id).update();
			jdbcClient.sql("UPDATE translation_memory SET status='PROCESSING' WHERE id=:id").param("id", id).update();
			translationRepository.fail(id, 3, code, now, Duration.ofSeconds(15));
			assertThat(jdbcClient.sql("SELECT status FROM translation_job WHERE translation_memory_id=:id")
				.param("id", id).query(String.class).single()).isEqualTo("FAILED");
		}
	}

	@Test
	void staleFailureCannotReopenCompletedTranslationJob() {
		UUID id = UUID.randomUUID();
		jdbcClient.sql("""
			INSERT INTO translation_memory (id,content_kind,source_locale,target_locale,translation_version,
			    source_hash,source_text,normalized_source_text,translated_text,status,model_id,prompt_version,
			    generated_at,created_at,updated_at)
			VALUES (:id,'NEWS_TITLE','ko','en','news-title-v3',repeat('a',64),'조 회장','조 회장',
			    'Chairman Jo','READY','gpt-5-nano','financial-title-translation-v8',now(),now(),now())
			""").param("id", id).update();
		jdbcClient.sql("""
			INSERT INTO translation_job (translation_memory_id,status,attempts,available_at,created_at,updated_at)
			VALUES (:id,'READY',1,now(),now(),now())
			""").param("id", id).update();
		translationRepository.fail(id, 1, "STALE_FAILURE", Instant.now(), Duration.ZERO);
		assertThat(jdbcClient.sql("SELECT status FROM translation_job WHERE translation_memory_id=:id")
			.param("id", id).query(String.class).single()).isEqualTo("READY");
		assertThat(jdbcClient.sql("SELECT status FROM translation_memory WHERE id=:id")
			.param("id", id).query(String.class).single()).isEqualTo("READY");
	}

	@Autowired
	com.kmarket.navigator.backend.disclosure.infrastructure.persistence.DisclosureDocumentRepairService documentRepair;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping requestMappingHandlerMapping;

	@MockitoBean
	DisclosureRagGateway disclosureRagGateway;

	@MockitoBean
	DisclosureDocumentHandler disclosureDocumentHandler;

	@MockitoBean
	DisclosureInsightGateway disclosureInsightGateway;

	@MockitoBean
	NewsAiGateway newsAiGateway;

	@MockitoBean
	AgentGateway agentGateway;

	@MockitoBean
	TaxDocumentGateway taxDocumentGateway;

	@MockitoBean
	GlobalPeerGateway globalPeerGateway;

	@MockitoBean
	TranslationAiGateway translationAiGateway;

	@Autowired
	ChatGenerationWorker chatGenerationWorker;

	@Autowired
	TaxDocumentWorker taxDocumentWorker;

	@Autowired
	TranslationWorker translationWorker;

	@Autowired
	com.kmarket.navigator.backend.translation.application.OnDemandTranslationService onDemandTranslationService;

	@Autowired
	com.kmarket.navigator.backend.translation.application.port.TranslationRepository translationRepository;

	@Autowired
	TaxDocumentProperties taxDocumentProperties;

	@Autowired
	AiServiceProperties aiServiceProperties;

	@Autowired
	NaverNewsProperties naverNewsProperties;

	@Autowired
	OpenDartProperties openDartProperties;

	@Test
	void contextLoads() {
	}

	@Test
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	void concurrentLanguagesShareOneJobAndExplicitFailedRequestsResumeOnce() throws Exception {
		UUID articleId = insertReadyNews("Concurrent news " + UUID.randomUUID(), "삼성전자가 새로운 투자를 발표했다.", Instant.now(), "HIGH");
		UUID clusterId = jdbcClient.sql("SELECT cluster_id FROM news_article WHERE id = :id")
			.param("id", articleId).query(UUID.class).single();
		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
			var tasks = new java.util.ArrayList<java.util.concurrent.Callable<com.kmarket.navigator.backend.translation.domain.TranslationView>>();
			for (int index = 0; index < 32; index++) {
				String locale = index % 2 == 0 ? "en" : "ko";
				tasks.add(() -> onDemandTranslationService.ensureNewsRequested(articleId, locale));
			}
			var results = executor.invokeAll(tasks);
			var ids = new java.util.HashSet<UUID>();
			for (var result : results) ids.add(result.get().jobId());
			assertThat(ids).hasSize(1);
			UUID jobId = ids.iterator().next();
			String sourceHash = results.getFirst().get().sourceHash();
			assertThat(translationRepository.findMany(com.kmarket.navigator.backend.translation.domain.TranslationKind.NEWS_NARRATIVE,
				List.of(sourceHash, "0".repeat(64)), "en", com.kmarket.navigator.backend.translation.application.OnDemandTranslationService.NEWS_VERSION)).containsOnlyKeys(sourceHash);
			assertThat(translationRepository.findMany(com.kmarket.navigator.backend.translation.domain.TranslationKind.NEWS_NARRATIVE,
				List.of(sourceHash), "ko", com.kmarket.navigator.backend.translation.application.OnDemandTranslationService.NEWS_VERSION)).isEmpty();
			var calls = new java.util.concurrent.atomic.AtomicInteger();
			when(translationAiGateway.streamNews(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
				calls.incrementAndGet();
				throw new com.kmarket.navigator.backend.translation.application.TranslationProviderException(
					com.kmarket.navigator.backend.translation.application.TranslationProviderException.Failure.INVALID_OUTPUT);
			});
			translationWorker.processNews();
			assertThat(calls.get()).isEqualTo(1);
			translationWorker.processNews();
			assertThat(calls.get()).isEqualTo(1);
			for (var result : executor.invokeAll(tasks)) {
				assertThat(result.get().jobId()).isEqualTo(jobId);
				assertThat(result.get().status()).isEqualTo(com.kmarket.navigator.backend.translation.domain.TranslationStatus.PENDING);
			}
			translationWorker.processNews();
			assertThat(calls.get()).isEqualTo(2);
			assertThat(jdbcClient.sql("SELECT attempts FROM translation_job WHERE translation_memory_id = :id")
				.param("id", jobId).query(Integer.class).single()).isEqualTo(1);
		} finally {
			jdbcClient.sql("DELETE FROM translation_memory WHERE request_context ->> 'article_id' = :id")
				.param("id", articleId.toString()).update();
			jdbcClient.sql("DELETE FROM news_article WHERE id = :id").param("id", articleId).update();
			jdbcClient.sql("DELETE FROM news_cluster WHERE id = :id").param("id", clusterId).update();
		}
	}

	@Test
	void usesBoundedProviderTimeoutsAndRecentNewsWindow() {
		assertThat(aiServiceProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(aiServiceProperties.readTimeout()).isEqualTo(Duration.ofSeconds(120));
		assertThat(naverNewsProperties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(naverNewsProperties.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
		assertThat(naverNewsProperties.getMaxArticleAge()).isEqualTo(Duration.ofHours(36));
		assertThat(naverNewsProperties.getTargetBatchSize()).isEqualTo(8);
		assertThat(naverNewsProperties.getQueries()).isEmpty();
		assertThat(openDartProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(openDartProperties.readTimeout()).isEqualTo(Duration.ofSeconds(150));
	}

	@Test
	void loadsNewsStockMappingsWithAliasesFromCurrentSchema() {
		var mappings = newsRepository.findStockMappings();

		assertThat(mappings).hasSize(75);
		assertThat(mappings)
			.allSatisfy(mapping -> assertThat(mapping.aliases()).isNotEmpty());
	}

	@Test
	void returnsDataDrivenTreatyRatesWithoutMakingEligibilityDetermination() throws Exception {
		mockMvc.perform(get("/api/v1/tax/countries"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.countryCode == 'US')]").exists());
		mockMvc.perform(post("/api/v1/tax/eligibility")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"residencyCountry":"US","investorType":"INDIVIDUAL"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.treatyDataAvailable").value(true))
			.andExpect(jsonPath("$.domesticDefaultRate").value(22.0))
			.andExpect(jsonPath("$.treatyDividendRate").value(15.0))
			.andExpect(jsonPath("$.sourceUrl").value(org.hamcrest.Matchers.containsString("taxlaw.nts.go.kr")))
			.andExpect(jsonPath("$.caveats[0]").exists());
		mockMvc.perform(post("/api/v1/tax/eligibility")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"residencyCountry":"ZZ","investorType":"INDIVIDUAL"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.treatyDataAvailable").value(false))
			.andExpect(jsonPath("$.treatyDividendRate").doesNotExist());
	}

	@Test
	void persistsSingletonTaxConversationAndReplacesItOnRestart() throws Exception {
		AuthFixture owner = signupAndLogin("taxconv_owner");
		AuthFixture other = signupAndLogin("taxconv_other");
		String authorization = "Bearer " + owner.accessToken();
		mockMvc.perform(get("/api/v1/me").header("Authorization", authorization)).andExpect(jsonPath("$.taxVerificationStatus").value("NOT_STARTED"));
		String opened = mockMvc.perform(post("/api/v1/me/tax-conversation")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"locale\":\"ko\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String roomId = objectMapper.readTree(opened).get("roomId").stringValue();
		mockMvc.perform(get("/api/v1/me").header("Authorization", authorization)).andExpect(jsonPath("$.taxVerificationStatus").value("IN_PROGRESS"));
		mockMvc.perform(post("/api/v1/me/tax-conversation/eligibility")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"locale\":\"ko\",\"residencyCountry\":\"US\",\"investorType\":\"INDIVIDUAL\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.roomId").value(roomId))
			.andExpect(jsonPath("$.eligibility.treatyDividendRate").value(15.0))
			.andExpect(jsonPath("$.guideDepth").value(0))
			.andExpect(jsonPath("$.verificationStarted").value(false));
		mockMvc.perform(post("/api/v1/me/tax-conversation/flow")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"SHOW_MORE_DETAIL\"}"))
			.andExpect(status().isConflict());
		mockMvc.perform(post("/api/v1/me/tax-conversation/flow")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"SHOW_GUIDE\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.guideDepth").value(1));
		mockMvc.perform(post("/api/v1/me/tax-conversation/flow")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"SHOW_MORE_DETAIL\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.guideDepth").value(2));
		mockMvc.perform(post("/api/v1/me/tax-conversation")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"locale\":\"en\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.roomId").value(roomId))
			.andExpect(jsonPath("$.locale").value("ko"))
			.andExpect(jsonPath("$.eligibility.treatyDividendRate").value(15.0));
		String resetBody = "{\"locale\":\"en\",\"roomId\":\"" + roomId + "\"}";
		mockMvc.perform(post("/api/v1/me/tax-conversation/restart")
			.header("Authorization", "Bearer " + other.accessToken()).contentType(MediaType.APPLICATION_JSON).content(resetBody))
			.andExpect(status().isNotFound());
		String restarted = mockMvc.perform(post("/api/v1/me/tax-conversation/restart")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(resetBody))
			.andExpect(status().isOk()).andExpect(jsonPath("$.eligibility").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(restarted).get("roomId").stringValue()).isNotEqualTo(roomId);
		mockMvc.perform(get("/api/v1/me").header("Authorization", authorization)).andExpect(jsonPath("$.taxVerificationStatus").value("IN_PROGRESS"));
		assertThat(jdbcClient.sql("SELECT count(*) FROM chat_room WHERE id = :id")
			.param("id", UUID.fromString(roomId)).query(Long.class).single()).isZero();
	}

	@Test
	void exposesVerifiedPackageAndInvalidatesItOnRestart() throws Exception {
		var owner = signupAndLogin("tax_package_owner");
		var other = signupAndLogin("tax_package_other");
		String auth = "Bearer " + owner.accessToken();
		startTaxVerification(owner);
		when(taxDocumentGateway.verify(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> new TaxDocumentVerification(
			call.getArgument(0), TaxDocumentStatus.VERIFIED,
			new TaxDocumentFields("Jane Investor", "US", "2026-01-10", null, "IRS", "TEST-100", "US", "US", "INDIVIDUAL"),
			List.of(), List.of(), new BigDecimal("0.97"), new BigDecimal("0.01"), false, "test-model", "test-version"));
		when(taxDocumentGateway.compare(any(), any(), any(), any())).thenReturn(new com.kmarket.navigator.backend.tax.domain.TaxDocumentComparison(
			"VERIFIED", List.of(), java.util.Map.of("matched", true), List.of(), "test-model"));
		var ids = new java.util.ArrayList<String>();
		for (var type : List.of("RESIDENCY_CERTIFICATE", "APOSTILLE", "REDUCED_TAX_APPLICATION")) {
			var file = new MockMultipartFile("file", type + ".pdf", "application/pdf", "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			var body = mockMvc.perform(multipart("/api/v1/me/tax-documents").file(file).param("documentType", type)
				.param("expectedResidencyCountry", "US").header("Authorization", auth)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
			ids.add(objectMapper.readTree(body).get("id").stringValue());
			taxDocumentWorker.process();
		}
		mockMvc.perform(get("/api/v1/me").header("Authorization", auth)).andExpect(jsonPath("$.taxVerificationStatus").value("IN_PROGRESS"));
		mockMvc.perform(post("/api/v1/me/tax-documents/comparison").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(java.util.Map.of("documentIds", ids)))).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/me").header("Authorization", auth)).andExpect(jsonPath("$.taxVerificationStatus").value("VERIFIED"))
			.andExpect(jsonPath("$.nationality").value("US")).andExpect(jsonPath("$.investorType").value("INDIVIDUAL"));
		mockMvc.perform(get("/api/v1/me/tax-review-package").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.documents.length()").value(3));
		mockMvc.perform(get("/api/v1/me/tax-review-package").header("Authorization", auth)).andExpect(jsonPath("$.fieldsRefreshing").value(true));
		when(taxDocumentGateway.verify(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> new TaxDocumentVerification(
			call.getArgument(0), TaxDocumentStatus.VERIFIED,
			new TaxDocumentFields("Jane Investor", "US", "2026-01-10", null, "IRS", "TEST-100", "US", "US", "INDIVIDUAL", "1985-06-15", "+15555551234", "100 Example Road, USA", 2),
			List.of(), List.of(), new BigDecimal("0.97"), new BigDecimal("0.01"), false, "test-model", "test-version"));
		taxDocumentWorker.backfillPreviewFields();
		mockMvc.perform(get("/api/v1/me/tax-review-package").header("Authorization", auth)).andExpect(jsonPath("$.fieldsRefreshing").value(false))
			.andExpect(jsonPath("$.previewFields[1].value").value("1985-06-15"))
			.andExpect(jsonPath("$.previewFields[3].value").value("+15555551234"))
			.andExpect(jsonPath("$.previewFields[6].value").value("100 Example Road, USA"));
		mockMvc.perform(get("/api/v1/me/tax-review-package").header("Authorization", "Bearer " + other.accessToken())).andExpect(status().isConflict());
		byte[] pdf = mockMvc.perform(get("/api/v1/me/tax-review-package/correction.pdf").header("Authorization", auth)).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store, private")).andReturn().getResponse().getContentAsByteArray();
		try (var parsed = org.apache.pdfbox.Loader.loadPDF(pdf)) {
			assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(parsed)).contains("Estimated correction request", "Jane Investor", "TEST-100", "1985-06-15", "100 Example Road, USA")
				.doesNotContain("예상 작성 경정청구서", "검증 문서에 근거한");
			assertThat(parsed.getDocumentCatalog().getAcroForm() == null || parsed.getDocumentCatalog().getAcroForm().getFields().isEmpty()).isTrue();
		}
		Files.createDirectories(java.nio.file.Path.of("build/qa")); Files.write(java.nio.file.Path.of("build/qa/estimated-correction.pdf"), pdf);
		byte[] koreanPdf = mockMvc.perform(get("/api/v1/me/tax-review-package/correction.pdf?locale=ko").header("Authorization", auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		try (var parsed = org.apache.pdfbox.Loader.loadPDF(koreanPdf)) { assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(parsed)).contains("예상 작성 경정청구서").doesNotContain("Estimated correction request"); }
		Files.write(java.nio.file.Path.of("build/qa/estimated-correction-ko.pdf"), koreanPdf);
		mockMvc.perform(get("/api/v1/me/tax-review-package?locale=invalid").header("Authorization", auth)).andExpect(status().isBadRequest());
		jdbcClient.sql("UPDATE tax_document SET extracted_fields = jsonb_set(extracted_fields, '{previewVersion}', '0') WHERE id = :id")
			.param("id", UUID.fromString(ids.get(2))).update();
		when(taxDocumentGateway.verify(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> new TaxDocumentVerification(
			call.getArgument(0), TaxDocumentStatus.VERIFIED,
			new TaxDocumentFields("Different Person", "US", "2026-01-10", null, "IRS", "TEST-100", "US", "US", "INDIVIDUAL", "1990-01-01", null, "Do not overwrite", 1),
			List.of(), List.of(), new BigDecimal("0.97"), new BigDecimal("0.01"), false, "test-model", "test-version"));
		taxDocumentWorker.backfillPreviewFields();
		assertThat(jdbcClient.sql("SELECT extracted_fields->>'holderName' FROM tax_document WHERE id = :id").param("id", UUID.fromString(ids.get(2))).query(String.class).single()).isEqualTo("Jane Investor");
		assertThat(jdbcClient.sql("SELECT preview_attempts FROM tax_document WHERE id = :id").param("id", UUID.fromString(ids.get(2))).query(Integer.class).single()).isEqualTo(1);
		var roomId = jdbcClient.sql("SELECT id FROM chat_room WHERE user_id = :id AND context_type = 'TAX_GUIDE' AND deleted_at IS NULL").param("id", owner.userId()).query(UUID.class).single();
		mockMvc.perform(post("/api/v1/me/tax-conversation/restart").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
			.content("{\"roomId\":\"" + roomId + "\",\"locale\":\"en\"}")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/me").header("Authorization", auth)).andExpect(jsonPath("$.taxVerificationStatus").value("IN_PROGRESS"));
		mockMvc.perform(get("/api/v1/me/tax-review-package/correction.pdf").header("Authorization", auth)).andExpect(status().isConflict());
	}

	@Test
	void encryptsValidatesVerifiesIsolatesAndPurgesTaxDocuments() throws Exception {
		when(taxDocumentGateway.verify(any(), any(), any(), any(), any(), any(), any()))
			.thenReturn(new TaxDocumentVerification(
				TaxDocumentType.RESIDENCY_CERTIFICATE,
				TaxDocumentStatus.VERIFIED,
				new TaxDocumentFields(
					"Jane Investor",
					"US",
					"2026-01-10",
					null,
					"IRS",
					"CERT-100",
					null,
					null,
					"INDIVIDUAL"
				),
				List.of(),
				List.of(new TaxDocumentIssue(
					"AUTHENTICITY_NOT_CONFIRMED",
					"INFO",
					"Screening does not constitute government approval."
				)),
				new BigDecimal("0.9700"),
				new BigDecimal("0.0200"),
				false,
				"gpt-5-mini",
				"tax-document-v1"
			));
		AuthFixture owner = signupAndLogin("tax_owner");
		AuthFixture other = signupAndLogin("tax_other");
		startTaxVerification(owner);
		byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"residency.pdf",
			"application/pdf",
			pdf
		);
		String responseBody = mockMvc.perform(multipart("/api/v1/me/tax-documents")
				.file(file)
				.param("documentType", "RESIDENCY_CERTIFICATE")
				.param("expectedResidencyCountry", "US")
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PROCESSING"))
			.andReturn().getResponse().getContentAsString();
		UUID documentId = UUID.fromString(objectMapper.readTree(responseBody).get("id").stringValue());
		String storageKey = jdbcClient.sql("SELECT storage_key FROM tax_document WHERE id = :id")
			.param("id", documentId)
			.query(String.class)
			.single();
		byte[] encrypted = Files.readAllBytes(taxDocumentProperties.root().resolve(storageKey));
		assertThat(encrypted).startsWith("KMTD1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		assertThat(encrypted).isNotEqualTo(pdf);

		taxDocumentWorker.process();
		mockMvc.perform(get("/api/v1/me/tax-documents/{documentId}", documentId)
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VERIFIED"))
			.andExpect(jsonPath("$.fields.residencyCountry").value("US"))
			.andExpect(jsonPath("$.modelId").value("gpt-5-mini"));
		mockMvc.perform(get("/api/v1/me/tax-documents/{documentId}", documentId)
				.header("Authorization", "Bearer " + other.accessToken()))
			.andExpect(status().isNotFound());

		mockMvc.perform(delete("/api/v1/me/tax-documents/{documentId}", documentId)
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isNoContent());
		jdbcClient.sql("UPDATE tax_document SET purge_after = CURRENT_TIMESTAMP WHERE id = :id")
			.param("id", documentId)
			.update();
		taxDocumentWorker.purgeDeleted();
		assertThat(Files.exists(taxDocumentProperties.root().resolve(storageKey))).isFalse();
		assertThat(jdbcClient.sql("SELECT purged_at IS NOT NULL FROM tax_document WHERE id = :id")
			.param("id", documentId)
			.query(Boolean.class)
			.single()).isTrue();

		when(taxDocumentGateway.verify(any(), any(), any(), any(), any(), any(), any()))
			.thenThrow(new com.kmarket.navigator.backend.global.error.BusinessException(
				com.kmarket.navigator.backend.global.error.ErrorCode.INVALID_TAX_DOCUMENT));
		String failedUpload = mockMvc.perform(multipart("/api/v1/me/tax-documents").file(file)
			.param("documentType", "RESIDENCY_CERTIFICATE").param("expectedResidencyCountry", "US")
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID failedId = UUID.fromString(objectMapper.readTree(failedUpload).get("id").stringValue());
		String failedKey = jdbcClient.sql("SELECT storage_key FROM tax_document WHERE id = :id")
			.param("id", failedId).query(String.class).single();
		taxDocumentWorker.process();
		mockMvc.perform(get("/api/v1/me/tax-documents/{id}", failedId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.contentAvailable").value(false));
		assertThat(Files.exists(taxDocumentProperties.root().resolve(failedKey))).isFalse();
		String replacement = mockMvc.perform(multipart("/api/v1/me/tax-documents").file(file)
			.param("documentType", "RESIDENCY_CERTIFICATE").param("expectedResidencyCountry", "US")
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID replacementId = UUID.fromString(objectMapper.readTree(replacement).get("id").stringValue());
		assertThat(replacementId).isNotEqualTo(failedId);
		String replacementKey = jdbcClient.sql("SELECT storage_key FROM tax_document WHERE id = :id")
			.param("id", replacementId).query(String.class).single();
		UUID roomId = jdbcClient.sql("SELECT id FROM chat_room WHERE user_id = :id AND context_type = 'TAX_GUIDE' AND deleted_at IS NULL")
			.param("id", owner.userId()).query(UUID.class).single();
		mockMvc.perform(post("/api/v1/me/tax-conversation/restart")
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"roomId\":\"" + roomId + "\",\"locale\":\"en\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.roomId").value(org.hamcrest.Matchers.not(roomId.toString())));
		assertThat(Files.exists(taxDocumentProperties.root().resolve(replacementKey))).isFalse();
		assertThat(jdbcClient.sql("SELECT count(*) FROM tax_document WHERE user_id = :id")
			.param("id", owner.userId()).query(Long.class).single()).isZero();
	}

	@Test
	void rejectsTaxDocumentsWithMismatchedOrActiveContentSignatures() throws Exception {
		AuthFixture owner = signupAndLogin("tax_invalid");
		startTaxVerification(owner);
		MockMultipartFile activePdf = new MockMultipartFile(
			"file",
			"attack.pdf",
			"application/pdf",
			"%PDF-1.7\n/OpenAction 1 0 R\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
		);
		mockMvc.perform(multipart("/api/v1/me/tax-documents")
				.file(activePdf)
				.param("documentType", "RESIDENCY_CERTIFICATE")
				.param("expectedResidencyCountry", "US")
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_TAX_DOCUMENT"));
	}

	@Test
	void generatesAndCachesGroundedGlobalPeerAnalysis() throws Exception {
		disclosureRepository.upsertCorporations(List.of(new OpenDartCorporation(
			"00126380",
			"삼성전자",
			"Samsung Electronics",
			"005930"
		)));
		activateCommonStocks("005930");
		when(globalPeerGateway.analyze(eq("005930"), any())).thenReturn(globalPeerAnalysis());

		for (int request = 0; request < 2; request++) {
			mockMvc.perform(get("/api/v1/market/stocks/{stockCode}/global-peers", "005930"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.primaryPeer.ticker").value("INTC"))
				.andExpect(jsonPath("$.comparisons.length()").value(3))
				.andExpect(jsonPath("$.keyStrengths.length()").value(4))
				.andExpect(jsonPath("$.financialDataAsOf").value("2025-12-31"));
		}
		verify(globalPeerGateway, times(1)).analyze(eq("005930"), any());
	}

	@Test
	void serviceStockUniverseContainsMvpStocks() throws Exception {
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
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM service_stock_catalog")
			.query(Long.class)
			.single()).isEqualTo(75);
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM security WHERE active AND common_stock")
			.query(Long.class)
			.single()).isEqualTo(75);
		mockMvc.perform(get("/api/v1/market/stocks/search")
				.param("query", "Samsung"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(10))
			.andExpect(jsonPath("$.items[0].nameEn").value(org.hamcrest.Matchers.containsStringIgnoringCase("Samsung")));
	}

	@Test
	void healthEndpointIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void publishesCompleteOpenApiContractAndSwaggerUi() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs")
				.header("X-Forwarded-Host", "api.example.test")
				.header("X-Forwarded-Proto", "https"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode openApi = objectMapper.readTree(body);
		assertThat(openApi.path("openapi").asString()).startsWith("3.0.");

		Set<String> applicationPaths = requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
			.flatMap(mapping -> mapping.getPatternValues().stream())
			.filter(path -> path.startsWith("/api/v1/"))
			.collect(Collectors.toUnmodifiableSet());
		Set<String> documentedPaths = Set.copyOf(openApi.path("paths").propertyNames());
		assertThat(documentedPaths).containsExactlyInAnyOrderElementsOf(applicationPaths);

		Set<String> httpMethods = Set.of("get", "post", "put", "patch", "delete");
		Set<String> applicationOperations = requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
			.flatMap(mapping -> mapping.getPatternValues().stream()
				.flatMap(path -> mapping.getMethodsCondition().getMethods().stream()
					.map(method -> method.name().toLowerCase() + " " + path)))
			.filter(operation -> operation.substring(operation.indexOf(' ') + 1).startsWith("/api/v1/"))
			.collect(Collectors.toUnmodifiableSet());
		Set<String> documentedOperations = openApi.path("paths").properties().stream()
			.flatMap(pathEntry -> pathEntry.getValue().properties().stream()
				.filter(operationEntry -> httpMethods.contains(operationEntry.getKey()))
				.map(operationEntry -> operationEntry.getKey() + " " + pathEntry.getKey()))
			.collect(Collectors.toUnmodifiableSet());
		assertThat(documentedOperations).containsExactlyInAnyOrderElementsOf(applicationOperations);

		openApi.path("paths").properties().forEach(pathEntry ->
			pathEntry.getValue().properties().stream()
				.filter(operationEntry -> httpMethods.contains(operationEntry.getKey()))
				.forEach(operationEntry -> {
					JsonNode operation = operationEntry.getValue();
					assertThat(operation.path("operationId").asString())
						.as(pathEntry.getKey() + " " + operationEntry.getKey() + " operationId")
						.isNotBlank();
					assertThat(operation.path("summary").asString())
						.as(pathEntry.getKey() + " " + operationEntry.getKey() + " summary")
						.isNotBlank();
					assertThat(operation.path("tags").isArray())
						.as(pathEntry.getKey() + " " + operationEntry.getKey() + " tags")
						.isTrue();
				})
		);

		assertThat(openApi.at("/components/securitySchemes/bearerAuth/type").asString())
			.isEqualTo("http");
		assertThat(openApi.path("paths").path("/api/v1/me").path("get").path("security").isArray())
			.isTrue();
		assertThat(openApi.path("paths").path("/api/v1/auth/login").path("post").path("security").isMissingNode())
			.isTrue();
		assertThat(openApi.path("paths").path("/api/v1/market/stocks").path("get").path("security").size())
			.isEqualTo(2);
		assertThat(openApi.path("paths").path("/api/v1/news/{articleId}/translation").path("post")
			.path("responses").has("202"))
			.isTrue();

		mockMvc.perform(get("/v3/api-docs.yaml"))
			.andExpect(status().isOk());
		String swaggerUi = mockMvc.perform(get("/swagger-ui/index.html"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(swaggerUi).contains("Swagger UI");
		mockMvc.perform(get("/swagger-ui.html"))
			.andExpect(status().is3xxRedirection());
	}

	@Test
	void disclosureListIsPublic() throws Exception {
		mockMvc.perform(get("/api/v1/disclosures"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void disclosureListReportsTheFullFiledDateCountWhenThePageIsLimited() throws Exception {
		LocalDate filedDate = LocalDate.of(2099, 1, 1);
		for (int index = 1; index <= 5; index++) {
			String receiptNumber = "2099010100000" + index;
			disclosureRepository.saveFiling(filingAt(receiptNumber, filedDate));
			publishDisclosureFixture(receiptNumber);
		}
		activateCommonStocks("005930");

		mockMvc.perform(get("/api/v1/disclosures")
				.param("from", filedDate.toString())
				.param("to", filedDate.toString())
				.param("limit", "4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(4))
			.andExpect(jsonPath("$.items[0].filedDateTotal").value(5))
			.andExpect(jsonPath("$.items[1].filedDateTotal").value(5))
			.andExpect(jsonPath("$.items[2].filedDateTotal").value(5))
			.andExpect(jsonPath("$.items[3].filedDateTotal").value(5));
	}

	@Test
	void disclosureListKeepsAReadyDetailVisibleWhileRagIndexingIsPending() throws Exception {
		String receiptNumber = "20990102000001";
		disclosureRepository.saveFiling(filingAt(receiptNumber, LocalDate.of(2099, 1, 2)));
		publishDisclosureFixture(receiptNumber);
		jdbcClient.sql("UPDATE disclosure SET index_status = 'PENDING' WHERE receipt_number = :receiptNumber")
			.param("receiptNumber", receiptNumber)
			.update();
		activateCommonStocks("005930");

		mockMvc.perform(get("/api/v1/disclosures")
				.param("from", "2099-01-02")
				.param("to", "2099-01-02"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].receiptNumber").value(receiptNumber));
		mockMvc.perform(get("/api/v1/disclosures/{receiptNumber}", receiptNumber))
			.andExpect(status().isOk());
	}

	@Test
	void permitsOnlyConfiguredFrontendOrigins() throws Exception {
		mockMvc.perform(options("/api/v1/news")
				.header("Origin", "https://kartkr.cloud")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://kartkr.cloud"))
			.andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS"));

		mockMvc.perform(get("/api/v1/news").header("Origin", "https://attacker.example"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void protectedAndUnknownApisRequireAuthenticationByDefault() throws Exception {
		mockMvc.perform(post("/api/v1/disclosures"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/disclosures/not-a-receipt"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void browserSessionSurvivesReloadWithoutExposingRefreshToken() throws Exception {
		AuthFixture fixture = signupAndLogin("browser");
		String loginId = jdbcClient.sql("SELECT login_id FROM user_account WHERE id=:id")
			.param("id", fixture.userId()).query(String.class).single();
		var loginResponse = mockMvc.perform(post("/api/v1/auth/browser/login")
			.header("Origin", "https://kartkr.cloud").header("X-KART-CSRF", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"%s\",\"password\":\"Secure!Pass123\"}".formatted(loginId)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
		String setCookie = loginResponse.getHeader("Set-Cookie");
		assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth/browser", "Max-Age=")
			.doesNotContain("Domain=");
		String refreshToken = loginResponse.getCookie("kart_browser_refresh").getValue();
		String firstAccess = objectMapper.readTree(loginResponse.getContentAsString()).get("accessToken").stringValue();
		var restored = mockMvc.perform(post("/api/v1/auth/browser/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}")
			.header("Origin", "https://kartkr.cloud").header("X-KART-CSRF", "1")
			.cookie(new jakarta.servlet.http.Cookie("kart_browser_refresh", refreshToken)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.user.loginId").value(loginId))
			.andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn().getResponse();
		String rotated = restored.getCookie("kart_browser_refresh").getValue();
		assertThat(rotated).isNotEqualTo(refreshToken);
		// 첫 응답을 받지 못한 브라우저가 같은 쿠키·요청 ID로 재접속해도 재사용 공격으로 처리하지 않는다.
		mockMvc.perform(post("/api/v1/auth/browser/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}")
			.header("Origin", "https://kartkr.cloud").header("X-KART-CSRF", "1")
			.cookie(new jakarta.servlet.http.Cookie("kart_browser_refresh", refreshToken)))
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie().value("kart_browser_refresh", rotated));
		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + firstAccess))
			.andExpect(status().isOk());
		String accessToken = objectMapper.readTree(restored.getContentAsString()).get("accessToken").stringValue();
		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/browser/logout")
			.header("Origin", "https://kartkr.cloud").header("X-KART-CSRF", "1")
			.cookie(new jakarta.servlet.http.Cookie("kart_browser_refresh", rotated)))
			.andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + firstAccess))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/auth/browser/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}")
			.header("Origin", "https://kartkr.cloud").header("X-KART-CSRF", "1")
			.cookie(new jakarta.servlet.http.Cookie("kart_browser_refresh", rotated)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void browserCookieEndpointsRejectCsrfAndPermitCredentialedTrustedPreflight() throws Exception {
		for (String action : List.of("refresh", "logout")) {
			mockMvc.perform(post("/api/v1/auth/browser/" + action).contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}").header("Origin", "https://kartkr.cloud"))
				.andExpect(status().isForbidden());
			mockMvc.perform(post("/api/v1/auth/browser/" + action).contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}").header("X-KART-CSRF", "1"))
				.andExpect(status().isForbidden());
			mockMvc.perform(post("/api/v1/auth/browser/" + action).contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}")
				.header("Origin", "https://attacker.example").header("X-KART-CSRF", "1"))
				.andExpect(status().isForbidden());
		}
		mockMvc.perform(post("/api/v1/auth/browser/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}")
			.header("Origin", "https://kartkr.cloud").header("X-KART-CSRF", "1"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(options("/api/v1/auth/browser/refresh")
			.header("Origin", "https://kartkr.cloud").header("Access-Control-Request-Method", "POST")
			.header("Access-Control-Request-Headers", "Content-Type,X-KART-CSRF"))
			.andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	@Test
	void issuesJwtAndRotatesRefreshTokenWithRedisSessionValidation() throws Exception {
		String loginId = "investor_" + UUID.randomUUID().toString().substring(0, 8);
		String password = "orange!8";
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "loginId": "%s",
					  "password": "%s",
					  "passwordConfirm": "%s",
					  "nationality": "US",
					  "investorType": "INDIVIDUAL",
					  "termsAccepted": true,
					  "privacyAccepted": true,
					  "fscDisclaimerAccepted": true
					}
					""".formatted(loginId, password, password)))
			.andExpect(status().isCreated());
		assertThat(jdbcClient.sql("SELECT count(*) FROM security_audit_event a JOIN user_account u ON u.id=a.user_id WHERE u.login_id=:id AND a.event_type='FSC_DISCLAIMER_ACCEPTED'")
			.param("id", loginId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbcClient.sql("SELECT password_hash FROM user_account WHERE login_id = :loginId")
			.param("loginId", loginId)
			.query(String.class)
			.single()).startsWith("{argon2}$argon2id$");

		JsonNode login = response(post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"loginId":"%s","password":"%s"}
				""".formatted(loginId, password)));
		String firstAccessToken = login.get("accessToken").stringValue();
		String firstRefreshToken = login.get("refreshToken").stringValue();
		assertThat(firstAccessToken.split("\\.")).hasSize(3);
		assertThat(firstRefreshToken).startsWith("kmr_");

		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + firstAccessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value(loginId));
		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + firstAccessToken + "x"))
			.andExpect(status().isUnauthorized());

		JsonNode refreshed = response(post("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"%s\"}".formatted(firstRefreshToken)));
		String secondAccessToken = refreshed.get("accessToken").stringValue();
		String secondRefreshToken = refreshed.get("refreshToken").stringValue();
		assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);

		mockMvc.perform(post("/api/v1/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(firstRefreshToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));

		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + secondAccessToken))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutImmediatelyInvalidatesJwtSession() throws Exception {
		String loginId = "logout_" + UUID.randomUUID().toString().substring(0, 8);
		String password = "Secure!Pass123";
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s",
					 "nationality":"GB","investorType":"INDIVIDUAL",
					 "termsAccepted":true,"privacyAccepted":true}
					""".formatted(loginId, password, password)))
			.andExpect(status().isCreated());
		JsonNode login = response(post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
		String accessToken = login.get("accessToken").stringValue();
		String refreshToken = login.get("refreshToken").stringValue();

		mockMvc.perform(post("/api/v1/auth/logout")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rateLimitsRepeatedLoginFailuresInRedis() throws Exception {
		String loginId = "limited_" + UUID.randomUUID().toString().substring(0, 8);
		String password = "Secure!Pass123";
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s",
					 "nationality":"CA","investorType":"INDIVIDUAL",
					 "termsAccepted":true,"privacyAccepted":true}
					""".formatted(loginId, password, password)))
			.andExpect(status().isCreated());

		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(post("/api/v1/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"loginId\":\"%s\",\"password\":\"Wrong!Pass123\"}".formatted(loginId)))
				.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)))
			.andExpect(status().isTooManyRequests())
			.andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank())
			.andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
	}

	@Test
	void managesWatchlistRecentItemsAndOwnedNotifications() throws Exception {
		OpenDartFiling disclosure = filing("20260818800679");
		disclosureRepository.saveFiling(disclosure);
		activateCommonStocks("005930");

		String loginId = "personal_" + UUID.randomUUID().toString().substring(0, 8);
		String password = "Secure!Pass123";
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s",
					 "nationality":"US","investorType":"INDIVIDUAL",
					 "termsAccepted":true,"privacyAccepted":true}
					""".formatted(loginId, password, password)))
			.andExpect(status().isCreated());
		JsonNode login = response(post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
		String accessToken = login.get("accessToken").stringValue();
		UUID userId = UUID.fromString(login.get("user").get("id").stringValue());

		for (int request = 0; request < 2; request++) {
			mockMvc.perform(put("/api/v1/me/watchlist/{stockCode}", "005930")
					.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stockCode").value("005930"));
		}
		mockMvc.perform(get("/api/v1/me/watchlist")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(1))
			.andExpect(jsonPath("$.items[0].nameKo").value("삼성전자"));

		OpenDartFiling watchedDisclosure = filing("20260818800680");
		disclosureRepository.saveFiling(watchedDisclosure);
		UUID watchedNewsId = insertReadyNews(
			"Samsung Electronics watchlist event",
			"A new event was detected for the watched company.",
			Instant.parse("2026-08-23T11:00:00Z"),
			"HIGH"
		);
		insertNewsCoverage(
			watchedNewsId,
			"Syndicated Samsung Electronics watchlist event",
			"Another publisher covered the same watchlist event."
		);
		assertThat(jdbcClient.sql("""
			SELECT COUNT(*) FROM user_notification
			WHERE user_id = :userId AND reference_id IN (:filingId, :newsId)
			""")
			.param("userId", userId)
			.param("filingId", watchedDisclosure.receiptNumber())
			.param("newsId", watchedNewsId.toString())
			.query(Long.class)
			.single()).isEqualTo(2L);
		jdbcClient.sql("""
			DELETE FROM user_notification
			WHERE user_id = :userId AND reference_id IN (:filingId, :newsId)
			""")
			.param("userId", userId)
			.param("filingId", watchedDisclosure.receiptNumber())
			.param("newsId", watchedNewsId.toString())
			.update();

		mockMvc.perform(post("/api/v1/me/recently-viewed")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"itemType\":\"STOCK\",\"referenceId\":\"005930\",\"stockCode\":\"005930\"}"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/me/recently-viewed")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"itemType":"FILING","referenceId":"%s","stockCode":"005930"}
					""".formatted(disclosure.receiptNumber())))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/me/recently-viewed")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		UUID notificationId = UUID.randomUUID();
		String otherLoginId = "personal_other_" + UUID.randomUUID().toString().substring(0, 8);
		String otherProfileBody = mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s",
					 "nationality":"GB","investorType":"INDIVIDUAL",
					 "termsAccepted":true,"privacyAccepted":true}
					""".formatted(otherLoginId, password, password)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode otherProfile = objectMapper.readTree(otherProfileBody);
		UUID otherUserId = UUID.fromString(otherProfile.get("id").stringValue());
		UUID otherNotificationId = UUID.randomUUID();
		jdbcClient.sql("""
			INSERT INTO user_notification (
			    id, user_id, notification_type, title, body, reference_type,
			    reference_id, created_at
			)
			VALUES (
			    :id, :userId, 'DISCLOSURE', 'New filing', 'A watched company filed a report.',
			    'FILING', :referenceId, CURRENT_TIMESTAMP
			)
			""")
			.param("id", notificationId)
			.param("userId", userId)
			.param("referenceId", disclosure.receiptNumber())
			.update();
		jdbcClient.sql("""
			INSERT INTO user_notification (
			    id, user_id, notification_type, title, body, created_at
			)
			VALUES (
			    :id, :userId, 'SYSTEM', 'Private notification', 'Another user owns this item.',
			    CURRENT_TIMESTAMP
			)
			""")
			.param("id", otherNotificationId)
			.param("userId", otherUserId)
			.update();

		mockMvc.perform(get("/api/v1/me/notifications")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(1))
			.andExpect(jsonPath("$.items[0].id").value(notificationId.toString()))
			.andExpect(jsonPath("$.items[0].read").value(false));
		mockMvc.perform(put("/api/v1/me/notifications/{notificationId}/read", notificationId)
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/v1/me/notifications/{notificationId}/read", otherNotificationId)
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		mockMvc.perform(get("/api/v1/me/notifications")
				.header("Authorization", "Bearer " + accessToken)
				.param("cursor", "not-a-cursor"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		mockMvc.perform(get("/api/v1/me/notifications")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(0))
			.andExpect(jsonPath("$.items[0].read").value(true));

		mockMvc.perform(delete("/api/v1/me/watchlist/{stockCode}", "005930")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isNoContent());
	}

	@Test
	void managesOwnedContextBoundChatRoomsWithOptimisticRenames() throws Exception {
		OpenDartFiling filing = filing("20260820800676");
		disclosureRepository.saveFiling(filing);
		activateCommonStocks("005930");
		disclosureRepository.completeDocumentJob(
			filing.receiptNumber(),
			List.of(document("c", "Semiconductor facility investment details")),
			List.of()
		);
		AuthFixture owner = signupAndLogin("chat_owner");
		AuthFixture other = signupAndLogin("chat_other");

		mockMvc.perform(get("/api/v1/me/chats"))
			.andExpect(status().isUnauthorized());
		JsonNode general = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"GENERAL\"}"));
		UUID generalId = UUID.fromString(general.get("id").stringValue());
		assertThat(general.get("context").get("type").stringValue()).isEqualTo("GENERAL");

		JsonNode filingRoom = objectMapper.readTree(mockMvc.perform(post("/api/v1/me/chats")
				.header("Authorization", "Bearer " + owner.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"contextType":"FILING","referenceId":"%s"}
					""".formatted(filing.receiptNumber())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.context.referenceId").value(filing.receiptNumber()))
			.andExpect(jsonPath("$.context.version").isString())
			.andReturn().getResponse().getContentAsString());
		assertThat(filingRoom.get("context").get("version").stringValue()).hasSize(64);

		mockMvc.perform(put("/api/v1/me/chats/{roomId}/name", generalId)
				.header("Authorization", "Bearer " + owner.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Korea market basics\",\"expectedVersion\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Korea market basics"))
			.andExpect(jsonPath("$.version").value(1));
		mockMvc.perform(put("/api/v1/me/chats/{roomId}/name", generalId)
				.header("Authorization", "Bearer " + owner.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Stale edit\",\"expectedVersion\":0}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CHAT_ROOM_VERSION_CONFLICT"));

		mockMvc.perform(get("/api/v1/me/chats")
				.header("Authorization", "Bearer " + owner.accessToken())
				.param("query", "market"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(generalId.toString()));
		mockMvc.perform(get("/api/v1/me/chats/{roomId}", generalId)
				.header("Authorization", "Bearer " + other.accessToken()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));

		mockMvc.perform(delete("/api/v1/me/chats/{roomId}", generalId)
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/me/chats/{roomId}", generalId)
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isNotFound());
		assertThat(jdbcClient.sql("""
			SELECT purge_after > deleted_at
			FROM chat_room
			WHERE id = :roomId
			""")
			.param("roomId", generalId)
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void generatesStopsAndRejectsRegenerationOfAnsweredMessages() throws Exception {
		AuthFixture owner = signupAndLogin("agent_owner");
		AuthFixture other = signupAndLogin("agent_other");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"GENERAL\"}"));
		UUID roomId = UUID.fromString(room.get("id").stringValue());
		UUID clientMessageId = UUID.randomUUID();
		when(agentGateway.answer(any(), eq("What is the KOSPI snapshot?"), any(), any(), any(), any()))
			.thenReturn(new AgentAnswer(
				"The supplied KOSPI snapshot is currently unavailable. [E1]",
				List.of("E1"),
				false,
				null,
				"KOSPI snapshot",
				"For information only.",
				new BigDecimal("0.85"),
				"test-agent",
				"market-agent-test-v1"
			));

		JsonNode submitted = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"clientMessageId":"%s","content":"What is the KOSPI snapshot?"}
				""".formatted(clientMessageId)));
		UUID generationId = UUID.fromString(submitted.get("generation").get("id").stringValue());
		assertThat(submitted.get("generation").get("status").stringValue()).isEqualTo("PENDING");

		JsonNode duplicate = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"clientMessageId":"%s","content":"What is the KOSPI snapshot?"}
				""".formatted(clientMessageId)));
		assertThat(duplicate.get("generation").get("id").stringValue())
			.isEqualTo(generationId.toString());

		chatGenerationWorker.process();
		JsonNode messages = response(get("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()));
		assertThat(messages).hasSize(2);
		assertThat(messages.get(1).get("role").stringValue()).isEqualTo("ASSISTANT");
		assertThat(messages.get(1).get("citations").get(0).get("id").stringValue()).isEqualTo("E1");
		UUID assistantId = UUID.fromString(messages.get(1).get("id").stringValue());
		mockMvc.perform(get("/api/v1/me/chats/{roomId}", roomId)
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("KOSPI snapshot"));

		when(agentGateway.answer(any(), eq("What is the KOSPI snapshot?"), any(), any(), any(), any()))
			.thenReturn(new AgentAnswer(
				"The current server snapshot is unavailable. [E1]",
				List.of("E1"), false, null, "KOSPI snapshot", "For information only.",
				new BigDecimal("0.80"), "test-agent", "market-agent-test-v1"
			));
		mockMvc.perform(post(
				"/api/v1/me/chats/{roomId}/messages/{assistantMessageId}/regenerate",
				roomId,
				assistantId
			)
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestKey\":\"%s\"}".formatted(UUID.randomUUID())))
			.andExpect(status().isNotFound());
		chatGenerationWorker.process();
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/messages", roomId)
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		JsonNode stoppedSubmission = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"clientMessageId":"%s","content":"Stop this request."}
				""".formatted(UUID.randomUUID())));
		UUID stoppedGenerationId = UUID.fromString(
			stoppedSubmission.get("generation").get("id").stringValue()
		);
		mockMvc.perform(post(
				"/api/v1/me/chats/{roomId}/generations/{generationId}/stop",
				roomId,
				stoppedGenerationId
			)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STOPPED"));
		mockMvc.perform(get(
				"/api/v1/me/chats/{roomId}/generations/{generationId}",
				roomId,
				generationId
			)
			.header("Authorization", "Bearer " + other.accessToken()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CHAT_GENERATION_NOT_FOUND"));
	}

	@Test
	void restoresLatestGenerationWithoutRetryingAndProtectsRoomOwnership() throws Exception {
		AuthFixture owner = signupAndLogin("generation_recovery");
		AuthFixture other = signupAndLogin("generation_intruder");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"GENERAL\"}"));
		UUID roomId = UUID.fromString(room.get("id").stringValue());
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/latest", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.generation").doesNotExist())
			.andExpect(header().string("Cache-Control", "no-store"));
		JsonNode submitted = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"clientMessageId\":\"%s\",\"content\":\"Recover this question.\"}".formatted(UUID.randomUUID())));
		String generationId = submitted.get("generation").get("id").stringValue();
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/latest", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.generation.id").value(generationId))
			.andExpect(jsonPath("$.generation.status").value("PENDING"));
		when(agentGateway.answer(any(), any(), any(), any(), any(), any()))
			.thenThrow(new com.kmarket.navigator.backend.global.error.BusinessException(
				com.kmarket.navigator.backend.global.error.ErrorCode.AI_SERVICE_UNAVAILABLE));
		chatGenerationWorker.process();
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/latest", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.generation.id").value(generationId))
			.andExpect(jsonPath("$.generation.status").value("FAILED"))
			.andExpect(jsonPath("$.generation.retryable").value(true));
		verify(agentGateway, times(1)).answer(any(), any(), any(), any(), any(), any());
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/latest", roomId)
			.header("Authorization", "Bearer " + other.accessToken())).andExpect(status().isNotFound());
		acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"clientMessageId\":\"%s\",\"content\":\"A newer question.\"}".formatted(UUID.randomUUID())));
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/latest", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.generation.status").value("PENDING"));
		mockMvc.perform(delete("/api/v1/me/chats/{roomId}", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/latest", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())).andExpect(status().isNotFound());
	}

	@Test
	void localizesPreviouslySavedNewsCitationsWithoutGeneratingAgain() throws Exception {
		activateCommonStocks("005930");
		UUID articleId = insertReadyNews("삼성전자 생산 소식", "삼성전자 생산 계획을 설명하는 원문입니다.", Instant.now(), "HIGH");
		AuthFixture owner = signupAndLogin("news_citation");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"NEWS\",\"referenceId\":\"%s\"}".formatted(articleId)));
		UUID roomId = UUID.fromString(room.get("id").stringValue());
		when(agentGateway.answer(any(), any(), any(), any(), any(), any())).thenReturn(new AgentAnswer(
			"The article describes a production update. [E1]", List.of("E1"), false, null,
			"Production update", "For information only.", new BigDecimal("0.8"), "test-agent", "test-v1"));
		acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"clientMessageId\":\"%s\",\"content\":\"What happened?\"}".formatted(UUID.randomUUID())));
		chatGenerationWorker.process();
		jdbcClient.sql("""
			UPDATE chat_message SET citations = jsonb_set(citations, '{0,title}', '"옛 한글 제목"')
			WHERE room_id = :room AND role = 'ASSISTANT'
			""").param("room", roomId).update();
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[1].citations[0].titleEn").value("Ready English news title"))
			.andExpect(jsonPath("$[1].citations[0].titleKo").value("삼성전자 생산 소식"));
		verify(agentGateway, times(1)).answer(any(), any(), any(), any(), any(), any());
	}

	@Test
	void questionLanguagePolicySurvivesUiChangesAndRetryWithoutRewritingHistory() throws Exception {
		AuthFixture owner = signupAndLogin("chat_locale");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"GENERAL\"}"));
		UUID roomId = UUID.fromString(room.get("id").stringValue());
		UUID requestId = UUID.randomUUID();
		String body = "{\"clientMessageId\":\"%s\",\"content\":\"위험이 무엇인가요?\",\"answerLocale\":\"ko\"}".formatted(requestId);
		when(agentGateway.answer(any(), any(), any(), any(), any(), eq("auto")))
			.thenThrow(new com.kmarket.navigator.backend.global.error.BusinessException(
				com.kmarket.navigator.backend.global.error.ErrorCode.AI_SERVICE_UNAVAILABLE))
			.thenReturn(new AgentAnswer("위험은 손실 가능성을 뜻합니다.", List.of(), false, null,
				"위험 설명", "정보 제공용입니다.", new BigDecimal("0.8"), "test-agent", "test-locale"));
		JsonNode submitted = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON).content(body));
		String generationId = submitted.get("generation").get("id").stringValue();
		JsonNode duplicate = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON).content(body));
		assertThat(duplicate.get("generation").get("id").stringValue()).isEqualTo(generationId);
		mockMvc.perform(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content(body.replace("\"ko\"", "\"en\"")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.generation.id").value(generationId));
		mockMvc.perform(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content(body.replace("\"ko\"", "\"fr\"")))
			.andExpect(status().isBadRequest());
		chatGenerationWorker.process();
		mockMvc.perform(post("/api/v1/me/chats/{roomId}/generations/{id}/retry", roomId, generationId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk());
		chatGenerationWorker.process();
		verify(agentGateway, times(2)).answer(any(), any(), any(), any(), any(), eq("auto"));
		assertThat(jdbcClient.sql("SELECT answer_locale FROM chat_generation WHERE id = :id")
			.param("id", UUID.fromString(generationId)).query(String.class).single()).isEqualTo("auto");
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[1].content").value("위험은 손실 가능성을 뜻합니다."));
		when(agentGateway.answer(any(), any(), any(), any(), any(), eq("auto"))).thenReturn(new AgentAnswer(
			"Risk means the possibility of loss.", List.of(), false, null, "Risk", "For information only.",
			new BigDecimal("0.8"), "test-agent", "test-locale"));
		acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"clientMessageId\":\"%s\",\"content\":\"Explain risk again.\"}".formatted(UUID.randomUUID())));
		chatGenerationWorker.process();
		verify(agentGateway).answer(any(), eq("Explain risk again."), any(), any(), any(), eq("auto"));
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(jsonPath("$.length()").value(4))
			.andExpect(jsonPath("$[1].content").value("위험은 손실 가능성을 뜻합니다."))
			.andExpect(jsonPath("$[3].content").value("Risk means the possibility of loss."));
	}

	@Test
	void persistsAndValidatesNewsSelectionWithoutAFilingSectionOrAnotherTranslation() throws Exception {
		activateCommonStocks("005930");
		String original = "삼성전자의 배당 계획은 아직 확정되지 않았다.";
		UUID articleId = insertReadyNews("삼성전자 배당 전망", original, Instant.now(), "HIGH");
		var cached = onDemandTranslationService.ensureNewsRequested(articleId, "en");
		translationRepository.claimForKind(com.kmarket.navigator.backend.translation.domain.TranslationKind.NEWS_NARRATIVE,
			1, "selection-test", Instant.now(), Instant.now().minusSeconds(300));
		translationRepository.complete(cached.jobId(), new GeneratedTranslation(cached.sourceHash(), "en", cached.translationVersion(),
			objectMapper.readTree("""
				{"translatedParagraphs":["Samsung Electronics' dividend plan is not confirmed."],
				 "what":"The dividend plan remains unconfirmed.","why":"No decision has been announced.","impact":"Future dividends remain uncertain."}
				"""), "test-model", "test-news-selection"), Instant.now());
		AuthFixture owner = signupAndLogin("news_selection");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"NEWS\",\"referenceId\":\"%s\"}".formatted(articleId)));
		UUID roomId = UUID.fromString(room.get("id").stringValue());
		when(agentGateway.answer(any(), any(), any(), any(), any(), any())).thenReturn(new AgentAnswer(
			"The plan is unconfirmed. [E1]", List.of("E1"), false, null, "Dividend plan", "For information only.",
			new BigDecimal("0.8"), "test-agent", "test-v1"));
		for (String selection : List.of("배당 계획은 아직 확정되지 않았다", "dividend plan is not confirmed")) {
			String body = objectMapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(),
				"content", "Explain the selected passage.", "selectedText", selection));
			JsonNode submitted = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
				.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON).content(body));
			JsonNode duplicate = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
				.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON).content(body));
			UUID generationId = UUID.fromString(submitted.path("generation").path("id").asString());
			assertThat(duplicate.path("generation").path("id")).isEqualTo(submitted.path("generation").path("id"));
			assertThat(jdbcClient.sql("SELECT selected_text FROM chat_generation WHERE id = :id AND selected_section_id IS NULL")
				.param("id", generationId).query(String.class).single()).isEqualTo(selection);
			chatGenerationWorker.process();
			mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/{generationId}", roomId, generationId)
				.header("Authorization", "Bearer " + owner.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.attempts").value(1));
		}
		org.mockito.ArgumentCaptor<List<com.kmarket.navigator.backend.chat.domain.AgentEvidence>> evidence = org.mockito.ArgumentCaptor.captor();
		verify(agentGateway, times(2)).answer(any(), any(), any(), evidence.capture(), any(), any());
		assertThat(evidence.getAllValues()).allSatisfy(items -> {
			var packet = objectMapper.readTree(items.getFirst().content());
			assertThat(packet.path("verifiedSelection").path("sourceHash").asString()).isEqualTo(cached.sourceHash());
			assertThat(packet.path("verifiedSelection").path("context").asString()).contains(packet.path("verifiedSelection").path("text").asString());
		});
		org.mockito.Mockito.verifyNoInteractions(translationAiGateway);
	}

	@Test
	void rejectsUnrelatedNewsSelectionBeforeCallingTheModel() throws Exception {
		activateCommonStocks("005930");
		UUID articleId = insertReadyNews("삼성전자 투자 계획", "삼성전자 투자 계획은 미정이다.", Instant.now(), "HIGH");
		AuthFixture owner = signupAndLogin("news_invalid");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"NEWS\",\"referenceId\":\"%s\"}".formatted(articleId)));
		UUID roomId = UUID.fromString(room.path("id").asString());
		mockMvc.perform(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "content", "Explain this.",
				"selectedText", "삼성전자", "selectedSectionId", UUID.randomUUID()))))
			.andExpect(status().isBadRequest());
		JsonNode submitted = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(),
				"content", "Explain this.", "selectedText", "An invented acquisition completed yesterday."))));
		chatGenerationWorker.process();
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/generations/{generationId}", roomId, submitted.path("generation").path("id").asString())
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_SELECTION"))
			.andExpect(jsonPath("$.retryable").value(false));
		org.mockito.Mockito.verifyNoInteractions(agentGateway, translationAiGateway);
	}

	@Test
	void disablesAutomaticAgentRetriesAndAllowsExplicitRetryAfterFailure() throws Exception {
		AuthFixture owner = signupAndLogin("agent_retry");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"contextType\":\"GENERAL\"}"));
		UUID roomId = UUID.fromString(room.get("id").stringValue());
		when(agentGateway.answer(any(), any(), any(), any(), any(), any()))
			.thenThrow(new com.kmarket.navigator.backend.global.error.BusinessException(
				com.kmarket.navigator.backend.global.error.ErrorCode.AI_SERVICE_UNAVAILABLE
			));
		JsonNode submitted = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"clientMessageId":"%s","content":"Give me the latest market view."}
				""".formatted(UUID.randomUUID())));
		UUID generationId = UUID.fromString(submitted.get("generation").get("id").stringValue());

		for (int attempt = 0; attempt < 3; attempt++) {
			chatGenerationWorker.process();
		}
		mockMvc.perform(get(
				"/api/v1/me/chats/{roomId}/generations/{generationId}",
				roomId,
				generationId
			)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.attempts").value(1))
			.andExpect(jsonPath("$.retryable").value(true))
			.andExpect(jsonPath("$.errorCode").value("AI_SERVICE_UNAVAILABLE"));
		mockMvc.perform(post(
				"/api/v1/me/chats/{roomId}/generations/{generationId}/retry",
				roomId,
				generationId
			)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.attempts").value(0));
	}

	@Test
	void reservesNewsTranslationClaimsWithoutWaitingForOlderFilingSections() {
		var now = java.time.Instant.now();
		var kind = com.kmarket.navigator.backend.translation.domain.TranslationKind.NEWS_NARRATIVE;
		var filingKind = com.kmarket.navigator.backend.translation.domain.TranslationKind.DISCLOSURE_SECTION;
		var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
		var context = mapper.createObjectNode();
		var filing = translationRepository.request(filingKind, "a".repeat(64), "공시", context,
			"en", "queue-isolation-test", now.minusSeconds(60));
		var news = translationRepository.request(kind, "b".repeat(64), "뉴스", context,
			"en", "queue-isolation-test", now);
		var newsJobs = translationRepository.claimForKind(kind, 2, "news-test", now, now.minusSeconds(300));
		assertThat(newsJobs).extracting(com.kmarket.navigator.backend.translation.domain.TranslationJob::id)
			.containsExactly(news.jobId());
		var filingJobs = translationRepository.claimForKind(filingKind, 2, "filing-test", now, now.minusSeconds(300));
		assertThat(filingJobs).extracting(com.kmarket.navigator.backend.translation.domain.TranslationJob::id)
			.containsExactly(filing.jobId());
	}

	@Test
	void preservesVerifiedSummaryWhenFailedBodyIsRequestedAgain() {
		var now = java.time.Instant.now();
		var kind = com.kmarket.navigator.backend.translation.domain.TranslationKind.NEWS_NARRATIVE;
		var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
		var context = mapper.createObjectNode();
		var source = "{\"paragraphs\":[\"원문\"],\"title\":\"제목\",\"content_availability\":\"FULL_ARTICLE\"}";
		var view = translationRepository.request(kind, "c".repeat(64), source, context,
			"en", "partial-cache-test", now);
		translationRepository.claimForKind(kind, 2, "partial-test", now, now.minusSeconds(300));
		var result = mapper.createObjectNode().put("what", "An investment was announced.")
			.put("why", "The company cited expansion.").put("impact", "No impact was stated.")
			.put("summaryReady", true).put("bodyReady", false);
		translationRepository.progress(view.jobId(), new com.kmarket.navigator.backend.translation.domain.GeneratedTranslation(
			view.sourceHash(), "en", view.translationVersion(), result, "gpt-5-nano", "test-prompt"), now);
		translationRepository.fail(view.jobId(), 1, "AI_GENERATION_INCOMPLETE", now, java.time.Duration.ZERO);
		var resumed = translationRepository.request(kind, view.sourceHash(), source, context,
			"en", view.translationVersion(), now.plusSeconds(1));
		assertThat(resumed.status()).isEqualTo(com.kmarket.navigator.backend.translation.domain.TranslationStatus.PENDING);
		assertThat(resumed.result()).isEqualTo(result);
		assertThat(resumed.modelId()).isEqualTo("gpt-5-nano");
	}

	@Test
	void isolatesFilingAgentToBoundDocumentVersionAndSelectedSection() throws Exception {
		OpenDartFiling filing = filing("20260821800677");
		disclosureRepository.saveFiling(filing);
		publishDisclosureFixture(filing.receiptNumber());
		activateCommonStocks("005930");
		disclosureRepository.completeDocumentJob(
			filing.receiptNumber(),
			List.of(document("d", "Revenue increased due to overseas demand.")),
			List.of()
		);
		jdbcClient.sql("UPDATE disclosure SET index_status = 'READY' WHERE receipt_number = :receiptNumber")
			.param("receiptNumber", filing.receiptNumber())
			.update();
		var detail = disclosureRepository.findByReceiptNumber(filing.receiptNumber()).orElseThrow();
		UUID sectionId = detail.documents().getFirst().sections().getFirst().id();
		when(disclosureRagGateway.ask(eq(filing.receiptNumber()), any()))
			.thenReturn(new DisclosureAnswer(
				"Revenue increased due to overseas demand. [C1]",
				false,
				null,
				List.of(new DisclosureAnswer.Citation(
					"C1",
					UUID.randomUUID(),
					detail.documents().getFirst().id(),
					1,
					List.of(sectionId),
					0,
					0,
					"Revenue",
					"Revenue increased due to overseas demand."
				)),
				"test-rag",
				"filing-rag-test-v1"
			));
		AuthFixture owner = signupAndLogin("filing_agent");
		JsonNode room = createdResponse(post("/api/v1/me/chats")
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"contextType":"FILING","referenceId":"%s"}
				""".formatted(filing.receiptNumber())));
		UUID roomId = UUID.fromString(room.get("id").stringValue());

		JsonNode submitted = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"clientMessageId":"%s","content":"Why did revenue increase?",
				 "selectedSectionId":"%s","selectedText":"overseas demand"}
				""".formatted(UUID.randomUUID(), sectionId)));
		chatGenerationWorker.process();
		mockMvc.perform(get("/api/v1/me/chats/{roomId}/messages", roomId)
				.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[1].citations[0].sourceType").value("FILING"))
			.andExpect(jsonPath("$[1].citations[0].titleEn").value("Translated disclosure title"))
			.andExpect(jsonPath("$[1].citations[0].titleKo").value(filing.reportName()))
			.andExpect(jsonPath("$[1].citations[0].url").value("/disclosures/" + filing.receiptNumber()))
			.andExpect(jsonPath("$[1].citations[0].referenceId").value(filing.receiptNumber()))
			.andExpect(jsonPath("$[1].citations[0].sectionIds[0]").value(sectionId.toString()));

		disclosureRepository.completeDocumentJob(
			filing.receiptNumber(),
			List.of(document("e", "A corrected disclosure version.")),
			List.of()
		);
		JsonNode stale = acceptedResponse(post("/api/v1/me/chats/{roomId}/messages", roomId)
			.header("Authorization", "Bearer " + owner.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"clientMessageId":"%s","content":"What changed?"}
				""".formatted(UUID.randomUUID())));
		UUID staleGenerationId = UUID.fromString(stale.get("generation").get("id").stringValue());
		chatGenerationWorker.process();
		mockMvc.perform(get(
				"/api/v1/me/chats/{roomId}/generations/{generationId}",
				roomId,
				staleGenerationId
			)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.errorCode").value("CHAT_CONTEXT_STALE"))
			.andExpect(jsonPath("$.retryable").value(false));
		mockMvc.perform(post("/api/v1/me/chats/{roomId}/generations/{generationId}/retry", roomId, staleGenerationId)
			.header("Authorization", "Bearer " + owner.accessToken()))
			.andExpect(status().isConflict());
		assertThat(submitted.get("generation").get("status").stringValue()).isEqualTo("PENDING");
	}

	@Test
	void servesSupportedMarketSearchScreenerQuotesAndForeignLimitSignals() throws Exception {
		disclosureRepository.saveFiling(filing("20260818800680"));
		disclosureRepository.saveFiling(new OpenDartFiling(
			"20260818800681",
			"00113526",
			"대한항공",
			"003490",
			CorporationClass.KOSPI,
			DisclosureType.PERIODIC,
			"반기보고서",
			"대한항공",
			LocalDate.of(2026, 8, 18),
			""
		));
		activateCommonStocks("005930", "003490");
		jdbcClient.sql("""
			UPDATE issuer i
			SET name_en = CASE s.stock_code
			    WHEN '005930' THEN 'Samsung Electronics'
			    WHEN '003490' THEN 'Korean Air'
			END
			FROM security s
			WHERE s.issuer_id = i.id AND s.stock_code IN ('005930', '003490')
			""").update();
		jdbcClient.sql("""
			UPDATE security
			SET sector = CASE stock_code
			    WHEN '005930' THEN 'Semiconductors'
			    WHEN '003490' THEN 'Airlines'
			END
			WHERE stock_code IN ('005930', '003490')
			""").update();

		UUID samsungId = securityId("005930");
		UUID koreanAirId = securityId("003490");
		jdbcClient.sql("""
			INSERT INTO market_quote_snapshot (
			    security_id, current_price_krw, change_amount_krw, change_rate,
			    open_price_krw, high_price_krw, low_price_krw, volume, market_session,
			    vi_active, single_price_trading, price_limit_state, trading_halted,
			    status_available, data_status, as_of, received_at, source
			)
			VALUES
			    (:samsungId, 78000, 1200, 1.5625, 77000, 78500, 76800, 15000000, 'REGULAR',
			     FALSE, FALSE, 'NONE', FALSE, TRUE, 'LIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'KIS_REST'),
			    (:koreanAirId, 24500, -200, -0.8097, 24700, 24900, 24300, 800000, 'REGULAR',
			     FALSE, FALSE, 'NONE', FALSE, TRUE, 'LIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'KIS_REST')
			""")
			.param("samsungId", samsungId)
			.param("koreanAirId", koreanAirId)
			.update();
		jdbcClient.sql("""
			INSERT INTO exchange_rate_snapshot (
			    currency, krw_per_unit, data_status, as_of, source
			)
			VALUES ('USD', 1300, 'LIVE', CURRENT_TIMESTAMP, 'TEST_FX')
			""").update();
		jdbcClient.sql("""
			INSERT INTO market_index_snapshot (
			    index_code, index_name, current_value, change_amount, change_rate,
			    volume, data_status, as_of, received_at, source
			)
			VALUES (
			    '0001', 'KOSPI', 2850.50, 10.20, 0.3591,
			    500000000, 'LIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'KIS_REST'
			)
			""").update();
		jdbcClient.sql("""
			INSERT INTO foreign_ownership_snapshot (
			    security_id, base_date, foreign_owned_quantity, total_listed_quantity,
			    foreign_limit_quantity, available_quantity, ownership_rate,
			    limit_exhaustion_rate, collected_at, source
			)
			VALUES
			    (:securityId, DATE '2026-08-20', 380000000, 1000000000, 450000000, 70000000,
			     38.0, 84.4444, CURRENT_TIMESTAMP, 'KRX'),
			    (:securityId, DATE '2026-08-21', 395000000, 1000000000, 450000000, 55000000,
			     39.5, 87.7778, CURRENT_TIMESTAMP, 'KRX'),
			    (:securityId, DATE '2026-08-22', 409500000, 1000000000, 450000000, 40500000,
			     40.95, 91.0, CURRENT_TIMESTAMP, 'KRX')
			""")
			.param("securityId", koreanAirId)
			.update();
		marketSnapshotRepository.saveForeignOwnership("005930", new ForeignOwnershipSnapshot(
			2_900_000_000L,
			5_969_782_550L,
			null,
			null,
			new BigDecimal("48.5779"),
			null,
			LocalDate.of(2026, 8, 22),
			Instant.now(),
			"TEST_KRX"
		));
		jdbcClient.sql("""
			INSERT INTO market_daily_price (
			    security_id, trading_date, open_price_krw, high_price_krw,
			    low_price_krw, close_price_krw, volume, source, collected_at
			)
			VALUES
			    (:securityId, DATE '2026-08-20', 75000, 77000, 74500, 76500, 10000000, 'KRX', CURRENT_TIMESTAMP),
			    (:securityId, DATE '2026-08-21', 76500, 78500, 76000, 78000, 15000000, 'KRX', CURRENT_TIMESTAMP)
			""")
			.param("securityId", samsungId)
			.update();

		String loginId = "market_" + UUID.randomUUID().toString().substring(0, 8);
		String password = "Secure!Pass123";
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s",
					 "nationality":"US","investorType":"INDIVIDUAL",
					 "termsAccepted":true,"privacyAccepted":true}
					""".formatted(loginId, password, password)))
			.andExpect(status().isCreated());
		JsonNode login = response(post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
		String accessToken = login.get("accessToken").stringValue();
		mockMvc.perform(put("/api/v1/me/watchlist/{stockCode}", "005930")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/market/stocks/search")
				.header("Authorization", "Bearer " + accessToken)
				.param("query", "005930")
				.param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(1))
			.andExpect(jsonPath("$.items[0].stockCode").value("005930"))
			.andExpect(jsonPath("$.items[0].watchlisted").value(true));
		mockMvc.perform(get("/api/v1/market/stocks")
				.header("Authorization", "Bearer " + accessToken)
				.param("watchlist", "true")
				.param("sort", "CHANGE_DESC"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(1))
			.andExpect(jsonPath("$.items[0].quote.status").value("LIVE"));
		mockMvc.perform(get("/api/v1/market/stocks").param("watchlist", "true"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mockMvc.perform(get("/api/v1/market/stocks")
				.param("sort", "CHANGE_ASC")
				.param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(1));
		mockMvc.perform(get("/api/v1/market/stocks")
				.param("sector", "Semiconductors")
				.param("minChangeRate", "1")
				.param("maxChangeRate", "2")
				.param("minVolume", "10000000")
				.param("maxVolume", "20000000"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(1))
			.andExpect(jsonPath("$.items[0].stockCode").value("005930"));
		mockMvc.perform(get("/api/v1/market/stocks")
				.param("minVolume", "200")
				.param("maxVolume", "100"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mockMvc.perform(get("/api/v1/market/stocks/{stockCode}", "005930"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentPriceUsd").value(60.0))
			.andExpect(jsonPath("$.subjectToForeignAcquisitionLimit").value(false))
			.andExpect(jsonPath("$.foreignOwnership.ownershipRate").value(48.5779))
			.andExpect(jsonPath("$.foreignOwnership.foreignLimitQuantity").doesNotExist())
			.andExpect(jsonPath("$.foreignLimitPrediction.status").value("NOT_APPLICABLE"));
		mockMvc.perform(get("/api/v1/market/foreign-limits"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].stock.stockCode").value("003490"))
			.andExpect(jsonPath("$[0].warning").value(true))
			.andExpect(jsonPath("$[0].prediction.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$[0].prediction.baseRate").doesNotExist())
			.andExpect(jsonPath("$[1].prediction.status").value("UNAVAILABLE"));
		mockMvc.perform(get("/api/v1/market/indices"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("LIVE"))
			.andExpect(jsonPath("$[1].status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$[2].indexName").value("KOSPI 200"));
		mockMvc.perform(get("/api/v1/market/exchange-rates/{currency}", "USD"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currency").value("USD"))
			.andExpect(jsonPath("$.krwPerUnit").value(1300))
			.andExpect(jsonPath("$.status").value("LIVE"))
			.andExpect(jsonPath("$.source").value("TEST_FX"));
		mockMvc.perform(get("/api/v1/market/stocks/{stockCode}/history", "005930"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[1].closePriceKrw").value(78000));
		mockMvc.perform(get("/api/v1/market/stocks/{stockCode}", "999999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_STOCK"));
	}

	@Test
	void servesFilteredNewsCursorDetailsAndEvidenceBoundTermExplanation() throws Exception {
		disclosureRepository.saveFiling(filing("20260823800001"));
		UUID firstArticleId = insertReadyNews(
			"Samsung Electronics announces rights offering",
			"The rights offering will finance a new semiconductor facility.",
			Instant.parse("2026-08-23T10:00:00Z"),
			"HIGH"
		);
		insertNewsCoverage(
			firstArticleId,
			"Syndicated semiconductor financing coverage",
			"A second publisher reported the same financing event."
		);
		UUID secondArticleId = insertReadyNews(
			"Samsung Electronics expands production",
			"The company will expand memory production.",
			Instant.parse("2026-08-23T09:00:00Z"),
			"MEDIUM"
		);
		AuthFixture newsOwner = signupAndLogin("news_owner");
		mockMvc.perform(put("/api/v1/me/watchlist/{stockCode}", "005930")
				.header("Authorization", "Bearer " + newsOwner.accessToken()))
			.andExpect(status().isOk());
		when(newsAiGateway.explainTerm(eq("rights offering"), any(), any(), any()))
			.thenReturn(new TermExplanation(
				"rights offering",
				"rights offering",
				"An issue of new shares offered to eligible holders.",
				"The company plans to raise funds for a semiconductor facility.",
				List.of(new TermReference("A1", "", "", "", null)),
				new BigDecimal("0.92"),
				false,
				true,
				null,
				"gpt-5-mini",
				"news-term-v1"
			));

		JsonNode firstPage = response(get("/api/v1/news")
			.param("stockCode", "005930")
			.param("sentiment", "POSITIVE")
			.param("limit", "1"));
		assertThat(firstPage.get("items")).hasSize(1);
		assertThat(firstPage.get("items").get(0).get("id").stringValue())
			.isEqualTo(firstArticleId.toString());
		assertThat(firstPage.get("nextCursor").stringValue()).isNotBlank();
		mockMvc.perform(get("/api/v1/news").param("watchlist", "true"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mockMvc.perform(get("/api/v1/news")
				.header("Authorization", "Bearer " + newsOwner.accessToken())
				.param("watchlist", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2));
		mockMvc.perform(get("/api/v1/news")
				.param("marketImpactImportance", "HIGH"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(firstArticleId.toString()))
			.andExpect(jsonPath("$.items[0].relatedCoverageCount").value(2));
		mockMvc.perform(get("/api/v1/news")
				.param("query", "second publisher"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));

		mockMvc.perform(get("/api/v1/news")
				.param("stockCode", "005930")
				.param("sentiment", "POSITIVE")
				.param("limit", "1")
				.param("cursor", firstPage.get("nextCursor").stringValue()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(secondArticleId.toString()))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		mockMvc.perform(get("/api/v1/news/{articleId}", firstArticleId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.analysisStatus").value("READY"))
			.andExpect(jsonPath("$.marketImpact").value("POSITIVE"))
			.andExpect(jsonPath("$.marketImpactImportance").value("HIGH"))
			.andExpect(jsonPath("$.marketImpactScore").value(0.55))
			.andExpect(jsonPath("$.contentAvailability").value("FULL_ARTICLE"))
			.andExpect(jsonPath("$.relatedStocks[0].stockCode").value("005930"));

		mockMvc.perform(post("/api/v1/news/{articleId}/term-explanations", firstArticleId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"selectedText\":\"rights offering\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.definition").value(
				"An issue of new shares offered to eligible holders."))
			.andExpect(jsonPath("$.sources[0].id").value("A1"))
			.andExpect(jsonPath("$.sufficientEvidence").value(true));
		assertThat(jdbcClient.sql("""
			SELECT COUNT(*) FROM financial_term_explanation_click WHERE article_id = :articleId
			""")
			.param("articleId", firstArticleId)
			.query(Long.class)
			.single()).isEqualTo(1);

		mockMvc.perform(post("/api/v1/news/{articleId}/term-explanations", firstArticleId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"selectedText\":\"text absent from the article\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_NEWS_SELECTION"));
	}

	@Test
	void reconcilesExistingCrossPublisherNewsAndReturnsOneStory() throws Exception {
		Instant publishedAt = Instant.now().minus(Duration.ofHours(80));
		UUID firstArticleId = insertReadyNews(
			"삼성전자 신규 투자 2500억원 발표",
			"삼성전자는 반도체 생산시설에 2500억원을 투자한다고 발표했다. 생산 능력 확충과 고객사의 수요 대응이 목적이다. 회사는 단계적으로 장비를 도입하고 기존 제조 공정의 효율을 개선할 계획이라고 설명했다. 신규 시설은 기존 사업장 안에 마련되며 별도 해외 공장 신설은 포함하지 않는다. 투자 집행 일정은 이사회 승인과 장비 인도 일정에 따라 결정된다. 회사는 계획의 주요 내용과 자금 조달 방안을 공시했다.",
			publishedAt,
			"HIGH"
		);
		UUID duplicateArticleId = insertReadyNews(
			"삼성전자 신규 투자 2천500억원 발표",
			"삼성전자는 반도체 생산시설에 2500억원을 투자한다고 발표했다. 생산 능력 확충과 고객사의 수요 대응이 목적이다. 회사는 단계적으로 장비를 도입하고 기존 제조 공정의 효율을 개선할 계획이라고 설명했다. 신규 시설은 기존 사업장 안에 마련되며 별도 해외 공장 신설은 포함하지 않는다. 투자 집행 일정은 이사회 승인과 장비 인도 일정에 따라 결정된다. 회사는 계획의 주요 내용과 자금 조달 방안을 공시했다.",
			publishedAt.plusSeconds(600),
			"HIGH"
		);
		jdbcClient.sql("UPDATE news_article SET publisher = 'wire.example.com' WHERE id = :id")
			.param("id", duplicateArticleId)
			.update();
		jdbcClient.sql("""
			UPDATE news_article SET collected_at = CURRENT_TIMESTAMP
			WHERE id IN (:first, :second)
			""")
			.param("first", firstArticleId)
			.param("second", duplicateArticleId)
			.update();

		newsClusterReconciliationService.reconcile();

		Long clusters = jdbcClient.sql("""
			SELECT COUNT(DISTINCT cluster_id) FROM news_article WHERE id IN (:first, :second)
			""")
			.param("first", firstArticleId)
			.param("second", duplicateArticleId)
			.query(Long.class)
			.single();
		assertThat(clusters).isEqualTo(1);
		mockMvc.perform(get("/api/v1/news").param("query", "신규 투자"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].relatedCoverageCount").value(2));
	}

	@Test
	void appliesNewsMaintenanceWithPhysicalDeletionAndExactStockMappings() {
		UUID retainedArticleId = insertReadyNews(
			"삼성전자 HBM 생산 투자 확대",
			"삼성전자는 평택 생산라인의 차세대 HBM 설비 투자를 확대한다고 발표했다.",
			Instant.now().minusSeconds(120),
			"HIGH"
		);
		UUID deletedArticleId = insertReadyNews(
			"유럽 축구 리그 결승전 결과",
			"우승팀이 연장전 끝에 승리했다.",
			Instant.now().minusSeconds(60),
			"LOW"
		);
		UUID clusterId = UUID.randomUUID();
		String version = "test-maintenance-" + UUID.randomUUID();

		newsRepository.applyNewsMaintenance(
			version,
			List.of(new NewsRetention(
				retainedArticleId,
				clusterId,
				clusterId.toString().replace("-", "").repeat(2),
				"삼성전자 hbm 생산 투자 확대",
				Map.of("005930", new BigDecimal("0.95"))
			)),
			List.of(deletedArticleId),
			Instant.now()
		);

		assertThat(newsRepository.newsMaintenanceApplied(version)).isTrue();
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM news_article WHERE id = :id")
			.param("id", deletedArticleId)
			.query(Long.class)
			.single()).isZero();
		var retained = newsRepository.findById(retainedArticleId).orElseThrow();
		assertThat(retained.clusterId()).isEqualTo(clusterId);
		assertThat(retained.relatedStocks())
			.extracting(stock -> stock.stockCode())
			.containsExactly("005930");
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
		publishDisclosureFixture(filing.receiptNumber());

		mockMvc.perform(get("/api/v1/disclosures").param("stockCode", "0126Z0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].stockCode").value("0126Z0"));
	}

	@Test
	void reusesReviewedEnglishTitleAcrossDisclosures() {
		OpenDartFiling first = new OpenDartFiling(
			"20260824000001", "00126380", "삼성전자", "005930",
			CorporationClass.KOSPI, DisclosureType.PERIODIC,
			"사업보고서 (2025.12)", "삼성전자", LocalDate.of(2026, 8, 24), ""
		);
		OpenDartFiling second = new OpenDartFiling(
			"20260824000002", "00126380", "삼성전자", "005930",
			CorporationClass.KOSPI, DisclosureType.PERIODIC,
			"사업보고서 (2025.12)", "삼성전자", LocalDate.of(2026, 8, 24), ""
		);
		disclosureRepository.saveFiling(first);
		disclosureRepository.saveFiling(second);
		activateCommonStocks("005930");

		assertThat(disclosureTitleTranslationWorker.processBatch(10)).isEqualTo(1);
		assertThat(disclosureRepository.findByReceiptNumber(first.receiptNumber()))
			.get()
			.extracting(detail -> detail.titleEn())
			.isEqualTo("Annual Report (2025.12)");
		assertThat(jdbcClient.sql("""
			SELECT count(*) FROM translation_memory
			WHERE content_kind = 'DISCLOSURE_TITLE'
			  AND normalized_source_text = '사업보고서 (2025.12)'
			""").query(Long.class).single()).isEqualTo(1);
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
		publishDisclosureFixture(filing.receiptNumber());

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
			.single()).isEqualTo("opendart-html-v6");
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
	void preservesSectionIdsOnReplayAndVersionsParserChanges() {
		var filing = filing("20260902999101");
		disclosureRepository.saveFiling(filing);
		var source = document("a", "first");
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(source), List.of());
		var original = jdbcClient.sql("SELECT payload_zstd FROM disclosure_document WHERE is_current")
			.query(byte[].class).single();
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(source), List.of());
		assertThat(jdbcClient.sql("SELECT payload_zstd FROM disclosure_document WHERE is_current")
			.query(byte[].class).single()).isEqualTo(original);
		jdbcClient.sql("UPDATE disclosure_document SET parser_version = 'opendart-html-v3'").update();
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(source), List.of());
		assertThat(jdbcClient.sql("SELECT version_no FROM disclosure_document WHERE is_current")
			.query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbcClient.sql("SELECT payload_zstd FROM disclosure_document WHERE NOT is_current")
			.query(byte[].class).single()).isEqualTo(original);
	}

	@Test
	void repairsLegacyTablesWithNewVersionAndKeepsBackupSource() {
		var filing = filing("20260902999102");
		disclosureRepository.saveFiling(filing);
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(document("a", "old section")), List.of());
		jdbcClient.sql("UPDATE disclosure_document SET parser_version = 'opendart-html-v3'").update();
		jdbcClient.sql("UPDATE ingestion_job SET status = 'COMPLETED' WHERE job_type = 'DISCLOSURE_SIGNAL'").update();
		var id = jdbcClient.sql("SELECT id FROM disclosure_document WHERE is_current").query(UUID.class).single();
		var disclosureId = jdbcClient.sql("SELECT disclosure_id FROM disclosure_document WHERE id = :id")
			.param("id", id).query(UUID.class).single();
		var previous = jdbcClient.sql("SELECT payload_zstd FROM disclosure_document WHERE id = :id")
			.param("id", id).query(byte[].class).single();
		var fixed = new OpenDartDocument(document("a", "old section").filename(), "a".repeat(64), "날짜 2026",
			"<table><tr><td>날짜</td><td>2026</td></tr></table>",
			List.of(new OpenDartSection(0, SectionKind.TABLE, null, "날짜 2026", "[[\"날짜\",\"2026\"]]")));
		assertThat(documentRepair.restoreVersion(id, disclosureId, filing.receiptNumber(), new byte[]{1}, fixed)).isFalse();
		assertThat(documentRepair.restoreVersion(id, disclosureId, filing.receiptNumber(), previous, fixed)).isTrue();
		assertThat(documentRepair.restoreVersion(id, disclosureId, filing.receiptNumber(), previous, fixed)).isFalse();
		assertThat(jdbcClient.sql("SELECT payload_zstd FROM disclosure_document WHERE id = :id")
			.param("id", id).query(byte[].class).single()).isEqualTo(previous);
		assertThat(jdbcClient.sql("SELECT version_no FROM disclosure_document WHERE is_current").query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbcClient.sql("SELECT status FROM ingestion_job WHERE job_type = 'DISCLOSURE_EMBEDDING'")
			.query(String.class).single()).isEqualTo("PENDING");
		assertThat(jdbcClient.sql("SELECT status FROM ingestion_job WHERE job_type = 'DISCLOSURE_SIGNAL'")
			.query(String.class).single()).isEqualTo("COMPLETED");
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void repairsVerifiedMainSourceInEitherDirectionAndPreservesOriginalVersion(boolean viewerToXml) {
		var filing = filing("20260902999103");
		disclosureRepository.saveFiling(filing);
		String originalFilename = filing.receiptNumber() + (viewerToXml ? ".viewer.html" : ".xml");
		var empty = new OpenDartDocument(originalFilename, "a".repeat(64), "", "", List.of());
		disclosureRepository.completeDocumentJob(filing.receiptNumber(), List.of(empty), List.of());
		jdbcClient.sql("UPDATE disclosure_document SET parser_version = 'opendart-html-v3'").update();
		var id = jdbcClient.sql("SELECT id FROM disclosure_document WHERE is_current").query(UUID.class).single();
		var disclosureId = jdbcClient.sql("SELECT disclosure_id FROM disclosure_document WHERE id=:id").param("id", id).query(UUID.class).single();
		var previous = jdbcClient.sql("SELECT payload_zstd FROM disclosure_document WHERE id=:id").param("id", id).query(byte[].class).single();
		var verified = new OpenDartDocument(filing.receiptNumber() + (viewerToXml ? ".xml" : ".viewer.html"), "b".repeat(64), "검증 원문",
			"<p>검증 원문</p>", List.of(new OpenDartSection(0, SectionKind.TEXT, null, "검증 원문", null)));
		var unrelated = new OpenDartDocument("other.viewer.html", verified.contentHash(), verified.bodyText(), verified.sanitizedHtml(), verified.sections());
		assertThatThrownBy(() -> documentRepair.restoreVersion(id, disclosureId, filing.receiptNumber(), previous, unrelated))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> documentRepair.restoreVersion(id, disclosureId, filing.receiptNumber(), previous, empty))
			.isInstanceOf(IllegalArgumentException.class);
		var archive = new StoredDocumentArchive(viewerToXml ? DocumentArchiveKind.OPENDART_ZIP : DocumentArchiveKind.DART_VIEWER_HTML, DocumentArchiveStatus.VERIFIED,
			"html-repair/20260902999103/verified.viewer.zip", "c".repeat(64), 100, null);
		assertThat(documentRepair.restoreVersion(id, disclosureId, filing.receiptNumber(), previous, verified, List.of(archive))).isTrue();
		assertThat(documentRepair.restoreVersion(id, disclosureId, filing.receiptNumber(), previous, verified, List.of(archive))).isFalse();
		assertThat(jdbcClient.sql("SELECT payload_zstd FROM disclosure_document WHERE id=:id AND NOT is_current")
			.param("id", id).query(byte[].class).single()).isEqualTo(previous);
		assertThat(jdbcClient.sql("SELECT source_filename FROM disclosure_document WHERE is_current").query(String.class).single())
			.isEqualTo(originalFilename);
		assertThat(jdbcClient.sql("SELECT content_hash FROM disclosure_document WHERE is_current").query(String.class).single()).isEqualTo("b".repeat(64));
		assertThat(jdbcClient.sql("SELECT parser_version FROM disclosure_document WHERE is_current").query(String.class).single()).isEqualTo("opendart-html-v6");
		assertThat(jdbcClient.sql("SELECT relative_path FROM disclosure_archive WHERE receipt_number=:receipt")
			.param("receipt", filing.receiptNumber()).query(String.class).single()).isEqualTo(archive.relativePath());
	}

	@Test
	void linksCorrectionFilingsToThePreviousVersion() throws Exception {
		OpenDartFiling original = filing("20260818800674");
		OpenDartFiling correction = new OpenDartFiling(
			"20260819800675",
			original.corpCode(),
			original.corporationName(),
			original.stockCode(),
			original.corporationClass(),
			original.disclosureType(),
			"[기재정정]기업설명회(IR) 개최",
			original.submitter(),
			original.filedDate().plusDays(1),
			"정"
		);

		disclosureRepository.saveFiling(correction);
		disclosureRepository.saveFiling(original);
		activateCommonStocks("005930");
		publishDisclosureFixture(correction.receiptNumber());

		var detail = disclosureRepository.findByReceiptNumber(correction.receiptNumber()).orElseThrow();
		assertThat(detail.versions()).hasSize(2);
		assertThat(detail.versions().getFirst().receiptNumber()).isEqualTo(original.receiptNumber());
		assertThat(detail.versions().getLast()).satisfies(version -> {
			assertThat(version.receiptNumber()).isEqualTo(correction.receiptNumber());
			assertThat(version.correctionOfReceiptNumber()).isEqualTo(original.receiptNumber());
			assertThat(version.current()).isTrue();
		});

		mockMvc.perform(get("/api/v1/disclosures/{receiptNumber}", correction.receiptNumber()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions.length()").value(2))
			.andExpect(jsonPath("$.versions[1].correctionOfReceiptNumber").value(original.receiptNumber()))
			.andExpect(jsonPath("$.versions[1].current").value(true));
	}

	@Test
	void filtersAndPaginatesDisclosureList() {
		disclosureRepository.saveFiling(filing("20260818800670"));
		disclosureRepository.saveFiling(filing("20260818800671"));
		activateCommonStocks("005930");
		publishDisclosureFixture("20260818800670");
		publishDisclosureFixture("20260818800671");

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
	void ordersSameDayDisclosuresByDisplayedDetectionTimeBeforeReceiptNumber() {
		OpenDartFiling earlier = filing("20260904800020");
		OpenDartFiling later = filing("20260904000054");
		disclosureRepository.saveFiling(earlier);
		disclosureRepository.saveFiling(later);
		activateCommonStocks("005930");
		publishDisclosureFixture(earlier.receiptNumber());
		publishDisclosureFixture(later.receiptNumber());
		jdbcClient.sql("""
			UPDATE disclosure
			SET detected_at = :detectedAt
			WHERE receipt_number = :receiptNumber
			""")
			.param("detectedAt", OffsetDateTime.parse("2026-08-18T08:05:00+09:00"))
			.param("receiptNumber", earlier.receiptNumber())
			.update();
		jdbcClient.sql("""
			UPDATE disclosure
			SET detected_at = :detectedAt
			WHERE receipt_number = :receiptNumber
			""")
			.param("detectedAt", OffsetDateTime.parse("2026-08-18T10:30:00+09:00"))
			.param("receiptNumber", later.receiptNumber())
			.update();

		var page = disclosureQueryHandler.findAll(
			new DisclosureListQuery("005930", LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18),
				Set.of(DisclosureType.MATERIAL_EVENT), null, 1)
		);
		assertThat(page.items()).extracting(DisclosureSummary::receiptNumber).containsExactly(later.receiptNumber());

		var nextPage = disclosureQueryHandler.findAll(
			new DisclosureListQuery("005930", LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18),
				Set.of(DisclosureType.MATERIAL_EVENT), DisclosureCursor.decode(page.nextCursor()), 1)
		);
		assertThat(nextPage.items()).extracting(DisclosureSummary::receiptNumber).containsExactly(earlier.receiptNumber());
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

	@Test
	void allowsAnonymousOnDemandNewsAndDisclosureTranslationRequests() throws Exception {
		UUID articleId = insertReadyNews(
			"Market disclosure translation test",
			"The company described a financing plan.",
			Instant.parse("2026-08-23T00:00:00Z"),
			"MEDIUM"
		);

		mockMvc.perform(post("/api/v1/news/{articleId}/translation", articleId))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"));
		mockMvc.perform(post("/api/v1/news/{articleId}/translation", articleId))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"));
		mockMvc.perform(post("/api/v1/news/{articleId}/translation?locale=ko", articleId))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"));
		mockMvc.perform(post("/api/v1/news/{articleId}/translation?locale=ko", articleId))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"));
		assertThat(jdbcClient.sql("""
			SELECT count(*)
			FROM translation_memory memory
			JOIN translation_job job ON job.translation_memory_id = memory.id
			WHERE memory.content_kind = 'NEWS_NARRATIVE'
			  AND memory.translation_version = 'news-bilingual-v1'
			  AND memory.request_context ->> 'article_id' = :articleId
			""")
			.param("articleId", articleId.toString())
			.query(Long.class)
			.single()).isEqualTo(1);

		OpenDartFiling filing = filing("20260823800003");
		disclosureRepository.saveFiling(filing);
		activateCommonStocks("005930");
		disclosureRepository.completeDocumentJob(
			filing.receiptNumber(),
			List.of(document("e", "The company approved a new facility.")),
			List.of()
		);
		publishDisclosureFixture(filing.receiptNumber());
		UUID sectionId = disclosureRepository.findByReceiptNumber(filing.receiptNumber()).orElseThrow()
			.documents().getFirst().sections().getFirst().id();

		mockMvc.perform(post(
			"/api/v1/disclosures/{receiptNumber}/sections/{sectionId}/translation",
			filing.receiptNumber(),
			sectionId
		))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"));
		mockMvc.perform(post(
			"/api/v1/disclosures/{receiptNumber}/sections/{sectionId}/translation",
			filing.receiptNumber(),
			sectionId
		))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"));
		assertThat(jdbcClient.sql("""
			SELECT count(*)
			FROM translation_memory memory
			JOIN translation_job job ON job.translation_memory_id = memory.id
			WHERE memory.content_kind = 'DISCLOSURE_SECTION'
			  AND memory.translation_version = 'disclosure-section-v4'
			  AND memory.request_context ->> 'section_id' = :sectionId
			""")
			.param("sectionId", sectionId.toString())
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void publishesOriginalNewsWhileTranslationAndThreeLineSummaryComplete() throws Exception {
		UUID articleId = insertReadyNews(
			"Samsung Electronics investment update",
			"The company announced a new semiconductor investment.",
			Instant.now().minusSeconds(60),
			"HIGH"
		);
		jdbcClient.sql("""
			UPDATE news_article
			SET english_body = NULL, what_summary = NULL, why_summary = NULL,
			    impact_summary = NULL, what_summary_ko = NULL, why_summary_ko = NULL,
			    impact_summary_ko = NULL
			WHERE id = :articleId
			""")
			.param("articleId", articleId)
			.update();

		mockMvc.perform(get("/api/v1/news/{articleId}", articleId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.originalBody").isNotEmpty());
		mockMvc.perform(post("/api/v1/news/{articleId}/translation", articleId))
			.andExpect(status().isAccepted());
		mockMvc.perform(post("/api/v1/news/{articleId}/translation?locale=ko", articleId))
			.andExpect(status().isAccepted());
		when(translationAiGateway.streamNews(any(), any(), any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> {
				String targetLocale = "en";
				var result = objectMapper.createObjectNode();
				result.putArray("translatedParagraphs")
					.add("ko".equals(targetLocale)
						? "회사는 새로운 반도체 투자를 발표했다."
						: "The company announced a new semiconductor investment.");
				result.put("what", "ko".equals(targetLocale)
					? "회사가 신규 반도체 투자를 발표했다."
					: "The company announced a semiconductor investment.");
				result.put("why", "ko".equals(targetLocale)
					? "생산 능력 확대가 필요했다."
					: "The filing cites capacity expansion.");
				result.put("impact", "ko".equals(targetLocale)
					? "향후 생산 능력이 늘어날 수 있다."
						: "The investment may increase future capacity.");
				var summaries = result.putObject("summaries");
				var en = summaries.putObject("en");
				for (String key : List.of("what", "why", "impact")) en.set(key, result.path(key));
				var ko = summaries.putObject("ko");
				ko.put("what", "회사가 신규 반도체 투자를 발표했다.");
				ko.put("why", "생산 능력 확대가 필요했다.");
				ko.put("impact", "향후 생산 능력이 늘어날 수 있다.");
				return new GeneratedTranslation(
					invocation.getArgument(0),
					targetLocale,
					invocation.getArgument(4),
					result,
					"translation-test-model",
					"news-narrative-v12"
				);
			});

		translationWorker.processNews();
		translationWorker.processNews();

		mockMvc.perform(get("/api/v1/news/{articleId}", articleId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.englishBody").value(
				"The company announced a new semiconductor investment."
			))
			.andExpect(jsonPath("$.whatEn").value(
				"The company announced a semiconductor investment."
			))
			.andExpect(jsonPath("$.whyEn").value("The filing cites capacity expansion."))
			.andExpect(jsonPath("$.impactEn").value(
				"The investment may increase future capacity."
			))
			.andExpect(jsonPath("$.whatKo").value("회사가 신규 반도체 투자를 발표했다."))
			.andExpect(jsonPath("$.whyKo").value("생산 능력 확대가 필요했다."))
			.andExpect(jsonPath("$.impactKo").value("향후 생산 능력이 늘어날 수 있다."));
	}

	@Test
	void storesCompletedNewsExpressionsWithoutLanguageRejectionOrRewriting() throws Exception {
		UUID articleId = insertReadyNews("Samsung Electronics results", "Revenue rose.",
			Instant.now().minusSeconds(60), "HIGH");
		jdbcClient.sql("""
			UPDATE news_article SET english_body = NULL, what_summary = NULL, why_summary = NULL,
			impact_summary = NULL, what_summary_ko = NULL, why_summary_ko = NULL, impact_summary_ko = NULL
			WHERE id = :articleId
			""").param("articleId", articleId).update();
		String expression = "Revenue is ₩700; the quoted label is 高.";
		when(translationAiGateway.streamNews(any(), any(), any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> {
				var result = objectMapper.createObjectNode().put("what", expression)
					.put("why", "No reason stated.").put("impact", "No impact stated.")
					.put("summaryReady", true).put("bodyReady", true);
				result.putArray("translatedParagraphs").add(expression);
				var summaries = result.putObject("summaries");
				summaries.putObject("en").put("what", expression)
					.put("why", "No reason stated.").put("impact", "No impact stated.");
				summaries.putObject("ko").put("what", "매출은 700원이다.")
					.put("why", "이유는 명시되지 않았다.").put("impact", "영향은 명시되지 않았다.");
				return new GeneratedTranslation(invocation.getArgument(0), "en",
					invocation.getArgument(4), result, "gpt-5-nano", "news-bilingual-stream-v7");
			});
		mockMvc.perform(post("/api/v1/news/{articleId}/translation", articleId))
			.andExpect(status().isAccepted());
		translationWorker.processNews();
		mockMvc.perform(get("/api/v1/news/{articleId}/translation", articleId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.result.translatedParagraphs[0]").value(expression));
		mockMvc.perform(get("/api/v1/news/{articleId}", articleId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.englishBody").value(expression))
			.andExpect(jsonPath("$.whatEn").value(expression))
			.andExpect(jsonPath("$.whatKo").value("매출은 700원이다."));
	}

	@Test
	void generatesAndCachesEvidenceBoundDisclosureInsightForCurrentDocumentVersion() throws Exception {
		OpenDartFiling filing = filing("20260823800002");
		disclosureRepository.saveFiling(filing);
		activateCommonStocks("005930");
		disclosureRepository.completeDocumentJob(
			filing.receiptNumber(),
			List.of(document("f", "The company approved a new semiconductor facility.")),
			List.of()
		);
		when(disclosureInsightGateway.summarize(
			eq(filing.receiptNumber()),
			eq(filing.reportName()),
			any()
		)).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			var evidence = (List<com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightEvidence>)
				invocation.getArgument(2);
			assertThat(evidence).hasSize(1);
			assertThat(evidence.getFirst().content()).contains("semiconductor facility");
			return new DisclosureInsightGeneration(
				"The company approved a new semiconductor facility.",
				"The filing does not state an additional reason.",
				"The facility may increase production capacity.",
				List.of(evidence.getFirst().id()),
				true,
				null,
				"gpt-5-nano",
				"filing-summary-v3",
				"회사가 신규 반도체 설비를 승인했습니다.",
				"공시에 추가 이유는 기재되어 있지 않습니다.",
				"설비가 생산 능력에 영향을 줄 수 있습니다."
			);
		});

		JsonNode generated = response(post(
			"/api/v1/disclosures/{receiptNumber}/insight",
			filing.receiptNumber()
		));
		assertThat(generated.get("what").stringValue()).contains("semiconductor facility");
		assertThat(generated.get("sourceSectionIds")).hasSize(1);
		assertThat(generated.get("contentVersionHash").stringValue()).hasSize(64);

		mockMvc.perform(get(
				"/api/v1/disclosures/{receiptNumber}/insight",
				filing.receiptNumber()
			))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sufficientEvidence").value(true))
			.andExpect(jsonPath("$.promptVersion").value("filing-summary-v3"))
			.andExpect(jsonPath("$.whatKo").value("회사가 신규 반도체 설비를 승인했습니다."));
		verify(disclosureInsightGateway, times(1)).summarize(
			eq(filing.receiptNumber()),
			eq(filing.reportName()),
			any()
		);
	}

	private static OpenDartFiling filing(String receiptNumber) {
		return filingAt(receiptNumber, LocalDate.of(2026, 8, 18));
	}

	private static GlobalPeerAnalysis globalPeerAnalysis() {
		var intel = globalPeer(1, "overall_business", "INTC", "Intel", "0.5201");
		var tsm = globalPeer(2, "semiconductor", "TSM", "Taiwan Semiconductor", "0.4942");
		var micron = globalPeer(3, "memory", "MU", "Micron Technology", "0.4870");
		return new GlobalPeerAnalysis(
			"005930",
			"삼성전자",
			"Samsung Electronics",
			"KOSPI",
			"Information Technology",
			"Semiconductors",
			"Consumer electronics and appliance manufacturing",
			"Samsung Electronics and its closest global peers",
			"The comparison is informational and not a one-for-one valuation substitute.",
			intel,
			List.of(intel, tsm, micron),
			List.of(
				new GlobalPeerAnalysis.Comparison("overall_business", "Overall reference.", intel),
				new GlobalPeerAnalysis.Comparison("semiconductor", "Foundry reference.", tsm),
				new GlobalPeerAnalysis.Comparison("memory", "Memory reference.", micron)
			),
			List.of(
				new GlobalPeerAnalysis.Strength("AI Technology", "AI capability.", "ai"),
				new GlobalPeerAnalysis.Strength("Consumer Devices", "Device reach.", "devices"),
				new GlobalPeerAnalysis.Strength("Foundry Capability", "Foundry scale.", "foundry"),
				new GlobalPeerAnalysis.Strength("Memory Technology", "Memory portfolio.", "memory")
			),
			new BigDecimal("0.5201"),
			"MEDIUM",
			LocalDate.of(2025, 12, 31),
			"global-peer-ranker-test-v1",
			"gpt-5-mini",
			"global-peer-narrative-v1",
			"TEST"
		);
	}

	private static GlobalPeerAnalysis.GlobalPeer globalPeer(
		int rank,
		String dimension,
		String ticker,
		String companyName,
		String similarity
	) {
		return new GlobalPeerAnalysis.GlobalPeer(
			dimension,
			rank,
			ticker,
			companyName,
			"https://financialmodelingprep.com/image-stock/" + ticker + ".png",
			"NASDAQ",
			"US",
			new BigDecimal(similarity),
			List.of("semiconductors"),
			"Information Technology",
			"Semiconductors",
			"Semiconductor design and manufacturing",
			"MEGA_CAP",
			2025,
			new BigDecimal("658355740000"),
			new BigDecimal("52853000000"),
			new BigDecimal("1000000000"),
			new BigDecimal("500000000"),
			"SEC_COMPANYFACTS",
			new BigDecimal("0.7760")
		);
	}

	private AuthFixture signupAndLogin(String prefix) throws Exception {
		String loginId = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
		String password = "Secure!Pass123";
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s",
					 "nationality":"US","investorType":"INDIVIDUAL",
					 "termsAccepted":true,"privacyAccepted":true}
					""".formatted(loginId, password, password)))
			.andExpect(status().isCreated());
		JsonNode login = response(post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
		return new AuthFixture(
			login.get("accessToken").stringValue(),
			UUID.fromString(login.get("user").get("id").stringValue())
		);
	}

	private void startTaxVerification(AuthFixture fixture) throws Exception {
		String authorization = "Bearer " + fixture.accessToken();
		mockMvc.perform(post("/api/v1/me/tax-conversation/eligibility")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"locale\":\"en\",\"residencyCountry\":\"US\",\"investorType\":\"INDIVIDUAL\"}"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/me/tax-conversation/flow")
			.header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"START_VERIFICATION\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.verificationStarted").value(true));
	}

	private record AuthFixture(String accessToken, UUID userId) {
	}

	private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
		throws Exception {
		String body = mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(body);
	}

	private JsonNode createdResponse(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
	) throws Exception {
		String body = mockMvc.perform(request)
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(body);
	}

	private JsonNode acceptedResponse(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
	) throws Exception {
		String body = mockMvc.perform(request)
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(body);
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
			"<p>" + text + "</p>",
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

	private UUID securityId(String stockCode) {
		return jdbcClient.sql("SELECT id FROM security WHERE stock_code = :stockCode")
			.param("stockCode", stockCode)
			.query(UUID.class)
			.single();
	}

	private void publishDisclosureFixture(String receiptNumber) {
		jdbcClient.sql("""
			UPDATE disclosure
			SET document_status = 'READY', index_status = 'READY', analysis_status = 'READY',
			    event_type = 'CORPORATE_ACTION', sentiment = 'NEUTRAL', importance = 'MEDIUM',
			    market_impact = 'NEUTRAL', market_impact_importance = 'MEDIUM',
			    market_impact_score = 0.5, event_confidence = 0.9,
			    sentiment_confidence = 0.8, importance_confidence = 0.8,
			    market_impact_confidence = 0.8, analysis_model_id = 'test-signal-model',
			    analyzed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
			WHERE receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.update();
		jdbcClient.sql("""
			UPDATE translation_memory memory
			SET translated_text = 'Translated disclosure title', status = 'READY',
			    model_id = 'test-reviewed-title', prompt_version = 'test-title-v1',
			    generated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
			FROM disclosure
			WHERE disclosure.receipt_number = :receiptNumber
			  AND memory.content_kind = 'DISCLOSURE_TITLE'
			  AND memory.source_hash = disclosure.title_source_hash
			  AND memory.target_locale = 'en'
			  AND memory.translation_version = 'codex-disclosure-title-v2'
			""")
			.param("receiptNumber", receiptNumber)
			.update();
	}

	private UUID insertReadyNews(
		String title,
		String excerpt,
		Instant publishedAt,
		String importance
	) {
		UUID clusterId = UUID.randomUUID();
		UUID articleId = UUID.randomUUID();
		String clusterHash = clusterId.toString().replace("-", "").repeat(2);
		String articleHash = articleId.toString().replace("-", "").repeat(2);
		var publishedAtUtc = java.time.OffsetDateTime.ofInstant(
			publishedAt,
			java.time.ZoneOffset.UTC
		);
		jdbcClient.sql("""
			INSERT INTO news_cluster (
			    id, signature_hash, normalized_title, created_at, updated_at
			)
			VALUES (:id, :hash, :title, :publishedAt, :publishedAt)
			""")
			.param("id", clusterId)
			.param("hash", clusterHash)
			.param("title", title.toLowerCase(java.util.Locale.ROOT))
			.param("publishedAt", publishedAtUtc)
			.update();
		jdbcClient.sql("""
			INSERT INTO news_article (
			    id, cluster_id, provider, provider_article_id, original_title,
			    title_source_hash,
			    original_excerpt, original_body, english_title, english_body, what_summary,
			    why_summary, impact_summary, event_type, sentiment, importance,
			    market_impact, market_impact_importance, market_impact_score,
			    event_confidence, sentiment_confidence,
			    importance_confidence, market_impact_confidence, original_url,
			    canonical_url, canonical_url_hash, publisher, content_availability, source_policy,
			    analysis_status, model_id, prompt_version, published_at, collected_at,
			    analyzed_at
			)
			VALUES (
			    :id, :clusterId, 'NAVER_NEWS', :providerId, :title,
			    encode(digest(regexp_replace(btrim(:title), '[[:space:]]+', ' ', 'g'), 'sha256'), 'hex'),
				    NULL, :excerpt, :englishTitle, 'This is a complete English article body.',
				    'A market event occurred.',
			    'The article states the reason.', 'The event may affect future operations.',
			    'CORPORATE_ACTION', 'POSITIVE', :importance, 'POSITIVE',
			    :importance, 0.55, 0.90, 0.85, 0.80, 0.75,
			    :url, :url, :hash, 'news.example.com',
			    'FULL_ARTICLE', 'publisher_public_article_v1', 'READY', 'gpt-5-mini', 'news-analysis-v1',
			    :publishedAt, :publishedAt, :publishedAt
			)
			""")
			.param("id", articleId)
			.param("clusterId", clusterId)
			.param("providerId", articleId.toString())
				.param("title", title)
				.param("englishTitle", "Ready English news title")
			.param("excerpt", excerpt)
			.param("importance", importance)
			.param("url", "https://news.example.com/" + articleId)
			.param("hash", articleHash)
			.param("publishedAt", publishedAtUtc)
			.update();
		jdbcClient.sql("""
			UPDATE news_cluster SET representative_article_id = :articleId WHERE id = :clusterId
			""")
			.param("articleId", articleId)
			.param("clusterId", clusterId)
			.update();
		jdbcClient.sql("""
			INSERT INTO news_article_security (article_id, security_id, match_confidence)
			VALUES (:articleId, :securityId, 0.99)
			""")
			.param("articleId", articleId)
			.param("securityId", securityId("005930"))
			.update();
		return articleId;
	}

	private UUID insertNewsCoverage(UUID representativeArticleId, String title, String excerpt) {
		UUID articleId = UUID.randomUUID();
		String articleHash = articleId.toString().replace("-", "").repeat(2);
		jdbcClient.sql("""
			INSERT INTO news_article (
			    id, cluster_id, provider, provider_article_id, original_title,
			    title_source_hash, original_excerpt, original_body, english_title,
			    english_body, what_summary, why_summary, impact_summary, event_type,
			    sentiment, importance, market_impact, market_impact_importance,
			    market_impact_score, event_confidence, sentiment_confidence,
			    importance_confidence, market_impact_confidence, original_url,
			    canonical_url, canonical_url_hash, publisher, thumbnail_url,
			    content_availability, source_policy, analysis_status, model_id, prompt_version,
			    published_at, collected_at, analyzed_at
			)
			SELECT :id, source.cluster_id, source.provider, :providerId, :title,
			       encode(digest(regexp_replace(btrim(:title), '[[:space:]]+', ' ', 'g'), 'sha256'), 'hex'),
				       :excerpt, source.original_body, source.english_title, source.english_body,
			       source.what_summary, source.why_summary, source.impact_summary,
			       source.event_type, source.sentiment, source.importance,
			       source.market_impact, source.market_impact_importance,
			       source.market_impact_score, source.event_confidence,
			       source.sentiment_confidence, source.importance_confidence,
			       source.market_impact_confidence, :url, :url, :hash,
			       'wire.example.com', source.thumbnail_url, source.content_availability,
			       source.source_policy, source.analysis_status, source.model_id, source.prompt_version,
			       source.published_at + INTERVAL '1 minute',
			       source.collected_at + INTERVAL '1 minute', source.analyzed_at
			FROM news_article source WHERE source.id = :representativeArticleId
			""")
			.param("id", articleId)
			.param("providerId", articleId.toString())
			.param("title", title)
			.param("excerpt", excerpt)
			.param("url", "https://wire.example.com/" + articleId)
			.param("hash", articleHash)
			.param("representativeArticleId", representativeArticleId)
			.update();
		jdbcClient.sql("""
			INSERT INTO news_article_security (article_id, security_id, match_confidence)
			VALUES (:articleId, :securityId, 0.99)
			""")
			.param("articleId", articleId)
			.param("securityId", securityId("005930"))
			.update();
		return articleId;
	}
}
