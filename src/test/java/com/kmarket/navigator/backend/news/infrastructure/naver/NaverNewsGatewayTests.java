package com.kmarket.navigator.backend.news.infrastructure.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

class NaverNewsGatewayTests {

	@Test
	void authenticatesAndMapsOnlyValidSearchItemsWithoutPretendingToHaveFullBody() {
		NaverNewsProperties properties = new NaverNewsProperties();
		properties.setEnabled(true);
		properties.setBaseUrl(URI.create("https://openapi.naver.example"));
		properties.setClientId("test-client");
		properties.setClientSecret("test-secret");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		NaverNewsGateway gateway = new NaverNewsGateway(
			builder.baseUrl(properties.getBaseUrl().toString()).build(),
			properties,
			new ObjectMapper()
		);

		server.expect(requestTo(containsString("/v1/search/news.json")))
			.andExpect(requestTo(containsString("query=")))
			.andExpect(requestTo(containsString("display=2")))
			.andExpect(header("X-Naver-Client-Id", "test-client"))
			.andExpect(header("X-Naver-Client-Secret", "test-secret"))
			.andRespond(withSuccess("""
				{
				  "items": [
				    {
				      "title": "<b>삼성전자</b> 투자 확대",
				      "description": "반도체 &amp; 설비 투자를 확대한다.",
				      "originallink": "https://news.example.com/article/1",
				      "link": "https://n.news.naver.com/article/1",
				      "pubDate": "Sun, 23 Aug 2026 12:00:00 +0900"
				    },
				    {
				      "title": "발행 시각 없음",
				      "description": "제외할 항목",
				      "link": "https://news.example.com/article/2"
				    }
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		var articles = gateway.search("삼성전자", 2);

		assertThat(articles).hasSize(1);
		assertThat(articles.getFirst().title()).isEqualTo("삼성전자 투자 확대");
		assertThat(articles.getFirst().excerpt()).isEqualTo("반도체 & 설비 투자를 확대한다.");
		assertThat(articles.getFirst().publisher()).isEqualTo("news.example.com");
		assertThat(articles.getFirst().originalUrl()).isEqualTo("https://news.example.com/article/1");
		server.verify();
	}
}
