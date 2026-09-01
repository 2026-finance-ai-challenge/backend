package com.kmarket.navigator.backend.global.config;

import static java.util.Map.entry;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	private static final String BEARER_AUTH = "bearerAuth";
	private static final Set<String> IMMEDIATE_OR_ACCEPTED_OPERATIONS = Set.of(
		"NewsController#requestTranslation",
		"DisclosureController#requestSectionTranslation"
	);
	private static final Map<String, ApiOperation> OPERATIONS = Map.ofEntries(
		entry("IdentityController#loginIdAvailability", operation("로그인 아이디 사용 가능 여부 조회", "Authentication")),
		entry("IdentityController#signUp", operation("회원가입", "Authentication", Access.PUBLIC, "201")),
		entry("IdentityController#login", operation("로그인 및 토큰 발급", "Authentication")),
		entry("IdentityController#refresh", operation("Refresh Token 회전", "Authentication")),
		entry("IdentityController#logout", operation("현재 세션 로그아웃", "Authentication", Access.AUTHENTICATED, "204")),
		entry("IdentityController#logoutAll", operation("모든 세션 로그아웃", "Authentication", Access.AUTHENTICATED, "204")),
		entry("IdentityController#profile", operation("내 프로필 조회", "Authentication", Access.AUTHENTICATED)),
		entry("IdentityController#changePassword", operation("비밀번호 변경", "Authentication", Access.AUTHENTICATED, "204")),
		entry("IdentityController#deleteAccount", operation("회원 탈퇴", "Authentication", Access.AUTHENTICATED, "204")),

		entry("PersonalizationController#watchlist", operation("관심종목 목록 조회", "Personalization", Access.AUTHENTICATED)),
		entry("PersonalizationController#addWatchlist", operation("관심종목 추가", "Personalization", Access.AUTHENTICATED)),
		entry("PersonalizationController#removeWatchlist", operation("관심종목 삭제", "Personalization", Access.AUTHENTICATED, "204")),
		entry("PersonalizationController#recordRecentlyViewed", operation("최근 조회 기록 저장", "Personalization", Access.AUTHENTICATED)),
		entry("PersonalizationController#recentlyViewed", operation("최근 조회 목록 조회", "Personalization", Access.AUTHENTICATED)),
		entry("PersonalizationController#notifications", operation("알림함 조회", "Personalization", Access.AUTHENTICATED)),
		entry("PersonalizationController#markAllNotificationsRead", operation("모든 알림 읽음 처리", "Personalization", Access.AUTHENTICATED, "204")),
		entry("PersonalizationController#markNotificationRead", operation("알림 읽음 처리", "Personalization", Access.AUTHENTICATED, "204")),

		entry("ChatRoomController#create", operation("AI 채팅방 생성", "AI Chat", Access.AUTHENTICATED, "201")),
		entry("ChatRoomController#findAll", operation("AI 채팅방 목록 조회", "AI Chat", Access.AUTHENTICATED)),
		entry("ChatRoomController#findOne", operation("AI 채팅방 상세 조회", "AI Chat", Access.AUTHENTICATED)),
		entry("ChatRoomController#rename", operation("AI 채팅방 이름 변경", "AI Chat", Access.AUTHENTICATED)),
		entry("ChatRoomController#delete", operation("AI 채팅방 삭제", "AI Chat", Access.AUTHENTICATED, "204")),
		entry("ChatMessageController#submit", operation("AI 채팅 메시지 제출", "AI Chat", Access.AUTHENTICATED, "202")),
		entry("ChatMessageController#messages", operation("AI 채팅 메시지 목록 조회", "AI Chat", Access.AUTHENTICATED)),
		entry("ChatMessageController#generation", operation("AI 답변 생성 상태 조회", "AI Chat", Access.AUTHENTICATED)),
		entry("ChatMessageController#stop", operation("AI 답변 생성 중단", "AI Chat", Access.AUTHENTICATED)),
		entry("ChatMessageController#retry", operation("실패한 AI 답변 재시도", "AI Chat", Access.AUTHENTICATED)),
		entry("ChatMessageController#regenerate", operation("AI 답변 다시 생성", "AI Chat", Access.AUTHENTICATED, "202")),

		entry("MarketController#search", operation("종목 검색", "Market", Access.OPTIONAL)),
		entry("MarketController#screener", operation("종목 스크리너 조회", "Market", Access.OPTIONAL)),
		entry("MarketController#stockDetail", operation("종목 상세 조회", "Market", Access.OPTIONAL)),
		entry("MarketController#indices", operation("시장 지수 조회", "Market")),
		entry("MarketController#exchangeRate", operation("환율 조회", "Market")),
		entry("MarketController#foreignLimits", operation("외국인 보유 한도 모니터 조회", "Market", Access.OPTIONAL)),
		entry("MarketController#foreignNetFlow", operation("시장 전체 외국인 순매수 조회", "Market")),
		entry("MarketController#history", operation("종목 가격 이력 조회", "Market")),
		entry("MarketController#globalPeers", operation("글로벌 피어 비교 조회", "Market")),

		entry("NewsController#findAll", operation("검증된 종목 뉴스 목록 조회", "News", Access.OPTIONAL)),
		entry("NewsController#findOne", operation("뉴스 상세 조회", "News")),
		entry("NewsController#findTranslation", operation("뉴스 번역 상태 조회", "News")),
		entry("NewsController#requestTranslation", operation("뉴스 번역 요청", "News")),
		entry("NewsController#explainTerm", operation("뉴스 금융용어 해설", "News", Access.OPTIONAL)),

		entry("DisclosureController#findInsight", operation("공시 AI 인사이트 조회", "Disclosures")),
		entry("DisclosureController#generateInsight", operation("공시 AI 인사이트 생성", "Disclosures")),
		entry("DisclosureController#findAll", operation("공시 목록 조회", "Disclosures")),
		entry("DisclosureController#findOne", operation("공시 상세 조회", "Disclosures")),
		entry("DisclosureController#findSectionTranslation", operation("공시 섹션 번역 상태 조회", "Disclosures")),
		entry("DisclosureController#requestSectionTranslation", operation("공시 섹션 번역 요청", "Disclosures")),
		entry("DisclosureController#ask", operation("공시 근거 기반 질의", "Disclosures")),
		entry("DisclosureController#requestIndexing", operation("공시 온디맨드 색인 요청", "Disclosures", Access.PUBLIC, "202")),

		entry("TaxEligibilityController#countries", operation("지원 국가별 조세조약 정보 조회", "Tax")),
		entry("TaxEligibilityController#eligibility", operation("조세조약 적용 가능성 확인", "Tax")),
		entry("TaxDocumentController#upload", operation("세무 문서 업로드", "Tax", Access.AUTHENTICATED, "202")),
		entry("TaxDocumentController#list", operation("내 세무 문서 목록 조회", "Tax", Access.AUTHENTICATED)),
		entry("TaxDocumentController#compare", operation("세무 문서 3종 교차 검증", "Tax", Access.AUTHENTICATED)),
		entry("TaxDocumentController#get", operation("내 세무 문서 상세 조회", "Tax", Access.AUTHENTICATED)),
		entry("TaxDocumentController#retry", operation("세무 문서 검증 재시도", "Tax", Access.AUTHENTICATED)),
		entry("TaxDocumentController#delete", operation("세무 문서 삭제", "Tax", Access.AUTHENTICATED, "204"))
	);

	@Bean
	OpenAPI kMarketOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("K-Market Navigator API")
				.version("v1")
				.description("한국 주식 시세·뉴스·공시·AI 분석·조세 정보를 제공하는 Backend API"))
			.servers(List.of(new Server().url("/").description("현재 접속 호스트")))
			.tags(List.of(
				new Tag().name("Authentication").description("회원가입, JWT 발급·회전, 계정 관리"),
				new Tag().name("Personalization").description("관심종목, 최근 조회, 알림함"),
				new Tag().name("AI Chat").description("근거 기반 AI Agent 채팅방과 메시지"),
				new Tag().name("Market").description("종목 검색, 스크리너, 시세, 지수, 글로벌 피어"),
				new Tag().name("News").description("종목 연관 뉴스 조회, 수집 전 중복 차단, 번역, 금융용어 해설"),
				new Tag().name("Disclosures").description("공시 조회, 번역, 인사이트, RAG 질의"),
				new Tag().name("Tax").description("조세조약 안내와 암호화 세무 문서 검증")
			))
			.components(new Components().addSecuritySchemes(
				BEARER_AUTH,
				new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")
					.description("로그인 API가 발급한 Access Token")
			));
	}

	@Bean
	OperationCustomizer kMarketOperationCustomizer() {
		return (operation, handlerMethod) -> {
			String key = handlerMethod.getBeanType().getSimpleName()
				+ "#"
				+ handlerMethod.getMethod().getName();
			ApiOperation documentation = OPERATIONS.get(key);
			if (documentation == null) {
				throw new IllegalStateException("OpenAPI documentation is missing for " + key);
			}
			operation.setOperationId(key.replace('#', '_'));
			operation.setSummary(documentation.summary());
			operation.setTags(List.of(documentation.tag()));
			applySecurity(operation, documentation.access());
			moveSuccessResponse(operation, documentation.successCode());
			addAcceptedResponse(operation, key);
			return operation;
		};
	}

	private static void addAcceptedResponse(
		io.swagger.v3.oas.models.Operation operation,
		String key
	) {
		if (!IMMEDIATE_OR_ACCEPTED_OPERATIONS.contains(key) || operation.getResponses() == null) {
			return;
		}
		var immediateResponse = operation.getResponses().get("200");
		var acceptedResponse = new ApiResponse().description("요청이 접수되어 비동기로 처리 중");
		if (immediateResponse != null) {
			acceptedResponse.setContent(immediateResponse.getContent());
		}
		operation.getResponses().addApiResponse("202", acceptedResponse);
	}

	private static void applySecurity(io.swagger.v3.oas.models.Operation operation, Access access) {
		if (access == Access.AUTHENTICATED) {
			operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
			return;
		}
		if (access == Access.OPTIONAL) {
			operation.setSecurity(List.of(
				new SecurityRequirement(),
				new SecurityRequirement().addList(BEARER_AUTH)
			));
		}
	}

	private static void moveSuccessResponse(
		io.swagger.v3.oas.models.Operation operation,
		String successCode
	) {
		if (successCode == null || "200".equals(successCode) || operation.getResponses() == null) {
			return;
		}
		var response = operation.getResponses().remove("200");
		if (response != null) {
			operation.getResponses().addApiResponse(successCode, response);
		}
	}

	private static ApiOperation operation(String summary, String tag) {
		return operation(summary, tag, Access.PUBLIC, null);
	}

	private static ApiOperation operation(String summary, String tag, Access access) {
		return operation(summary, tag, access, null);
	}

	private static ApiOperation operation(
		String summary,
		String tag,
		Access access,
		String successCode
	) {
		return new ApiOperation(summary, tag, access, successCode);
	}

	private enum Access {
		PUBLIC,
		OPTIONAL,
		AUTHENTICATED
	}

	private record ApiOperation(String summary, String tag, Access access, String successCode) {
	}
}
