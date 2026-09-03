package com.kmarket.navigator.backend.news.infrastructure.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class PublisherOriginalArticleGatewayTests {
	@Test
	void decodesKoreanPublisherCharsetWithoutReplacementCharacters() throws Exception {
		var gateway = new PublisherOriginalArticleGateway(HttpClient.newHttpClient(), Duration.ofSeconds(1));
		String html = "<html><meta charset='euc-kr'><div id='jose_news_view'>현대차와 기아의 판매량을 발표했다.</div></html>";
		byte[] bytes = html.getBytes(java.nio.charset.Charset.forName("MS949"));
		assertThat(gateway.decodeHtml(bytes, "text/html; charset=euc-kr", java.net.URI.create("https://example.com")))
			.contains("현대차와 기아의 판매량").doesNotContain("\uFFFD");
		assertThat(gateway.decodeHtml(bytes, "text/html", java.net.URI.create("https://example.com")))
			.contains("현대차와 기아의 판매량").doesNotContain("\uFFFD");
	}

	@Test
	void preservesRawTextWhenUnrelatedParagraphsArePresent() {
		var gateway = new PublisherOriginalArticleGateway(HttpClient.newHttpClient(), Duration.ofSeconds(1));
		String article = "양종희 KB금융 회장이 차기 회장 후보로 선발됐다. "
			+ "회사는 지난해 실적 개선과 주주환원 확대 성과를 설명했다. "
			+ "이사회는 독립적인 심사를 거쳐 차기 회장을 결정하며 다음 달 인터뷰 결과를 발표할 계획이다.";
		var document = Jsoup.parse("<main><div id='DivArticleContent'><p class='teditor'>유료페이지 안내</p>"
			+ article + "<br><br>추가 본문이다.</div><article class='list_news'><p>Best Click</p>"
			+ "<p>다른 뉴스 목록만 길게 나오는 잘못된 본문이다.</p></article></main>");
		assertThat(gateway.findArticleBody(document)).contains(article, "추가 본문이다.")
			.doesNotContain("Best Click", "유료페이지 안내");
	}

	@Test
	void neverUsesPageNavigationWhenSpecificArticleIsUnavailable() {
		var gateway = new PublisherOriginalArticleGateway(HttpClient.newHttpClient(), Duration.ofSeconds(1));
		var document = Jsoup.parse("<main><div id='articleBody'>로그인이 필요합니다.</div><article><p>"
			+ "다른 뉴스와 메뉴 목록 ".repeat(40) + "</p></article></main>");
		assertThat(gateway.findArticleBody(document)).isEmpty();
	}

	@Test
	void retainsPlainBodyAlongsideParagraphBlocks() {
		var gateway = new PublisherOriginalArticleGateway(HttpClient.newHttpClient(), Duration.ofSeconds(1));
		var document = Jsoup.parse("<article id='articleBody'>"
			+ "알테오젠은 지난 종가 대비 하락했다. 거래량은 늘었으며 회사는 신규 생산 시설 투자 계획을 발표했다. ".repeat(3)
			+ "<div class='vote-widget'><p>슈퍼스타 브랜드 파워 투표</p><p>다른 연예인 투표</p></div>"
			+ "<p>후속 문단이다.</p><p>마지막 문단이다.</p></article>");
		assertThat(gateway.findArticleBody(document)).startsWith("알테오젠은").contains("후속 문단이다.")
			.doesNotContain("슈퍼스타", "연예인");
	}

	@Test
	void prefersSpecificBodyOverLongerPageContainer() {
		var gateway = new PublisherOriginalArticleGateway(
			HttpClient.newHttpClient(),
			Duration.ofSeconds(1)
		);
		var document = Jsoup.parse("""
			<html><body><main>
			  <article id="articleBody">
			    <p>삼성전자는 차세대 반도체 설비 투자 계획을 발표했다.</p>
			    <p>신규 라인은 단계적으로 가동할 예정이다.</p>
			    <p>회사는 고객 수요와 공급망 상황을 확인하며 생산량을 안정적으로 늘릴 계획이라고 설명했다.</p>
			  </article>
			  <section><p>관련 기사 SK하이닉스 실적 뉴스와 다른 종목 목록이 여기에 반복되어 표시된다.</p></section>
			</main></body></html>
			""");

		String body = gateway.findArticleBody(document);

		assertThat(body)
			.contains("삼성전자는 차세대 반도체")
			.doesNotContain("관련 기사 SK하이닉스");
	}
}
