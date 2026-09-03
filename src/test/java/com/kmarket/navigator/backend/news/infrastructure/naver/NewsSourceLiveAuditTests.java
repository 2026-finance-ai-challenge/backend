package com.kmarket.navigator.backend.news.infrastructure.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.kmarket.navigator.backend.news.application.NewsStockMatcher;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;

@EnabledIfEnvironmentVariable(named = "NEWS_SOURCE_LIVE_AUDIT", matches = "1")
class NewsSourceLiveAuditTests {
	@Test
	void extractsActualArticlesWithoutRankingsOrPolls() {
		var gateway = new PublisherOriginalArticleGateway(HttpClient.newHttpClient(), Duration.ofSeconds(20));
		var cases = List.of(
			List.of("https://www.topstarnews.net/news/articleView.html?idxno=16179151", "알테오젠", "196170"),
			List.of("https://m.thebell.co.kr/m/newsview.asp?newskey=202608281645448080103894&svccode=00", "KB금융", "105560"),
			List.of("https://biz.heraldcorp.com/article/10858582?ref=naver", "셀트리온", "068270"),
			List.of("https://www.ccdailynews.com/news/articleView.html?idxno=2437496", "에코프로", "086520"),
			List.of("http://www.joseilbo.com/news/news_read.php?uid=574876&class=17&grp=", "기아", "000270")
		);
		for (var source : cases) {
			var result = gateway.fetch(source.get(0)).orElseThrow();
			assertThat(result.body()).contains(source.get(1)).doesNotContain("Best Click", "슈퍼스타 브랜드 파워", "투표하기");
			var mappings = List.of(new NewsStockMapping(source.get(2), source.get(1), source.get(1), "KOSPI", List.of()));
			assertThat(new NewsStockMatcher().verifiedArticleMatches(source.get(1) + " 투자 소식", result.body(), mappings))
				.containsKey(source.get(2));
			System.out.printf("LIVE_NEWS_SOURCE stock=%s chars=%d policy=%s%n", source.get(2), result.body().length(), result.sourcePolicy());
		}
	}
}
