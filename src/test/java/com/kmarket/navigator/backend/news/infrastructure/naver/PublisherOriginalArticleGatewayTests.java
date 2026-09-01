package com.kmarket.navigator.backend.news.infrastructure.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class PublisherOriginalArticleGatewayTests {

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
