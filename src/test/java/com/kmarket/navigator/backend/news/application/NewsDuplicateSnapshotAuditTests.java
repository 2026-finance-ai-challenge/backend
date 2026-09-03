package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "NEWS_DUPLICATE_CANDIDATES", matches = ".+")
class NewsDuplicateSnapshotAuditTests {
	@Test
	void auditsReviewedCopiesAgainstOriginalBodies() throws Exception {
		var mapper = JsonMapper.builder().build();
		var snapshot = mapper.readTree(Files.readString(Path.of(System.getenv("NEWS_RELEVANCE_SNAPSHOT"))));
		var candidates = mapper.readTree(Files.readString(Path.of(System.getenv("NEWS_DUPLICATE_CANDIDATES"))));
		var articles = new HashMap<String, JsonNode>();
		for (var article : snapshot.path("articles")) articles.put(article.path("id").asString(), article);
		var fingerprint = new NewsFingerprint();
		int checked = 0;
		int copies = 0;
		for (var candidate : candidates.path("near_duplicate_candidates")) {
			var first = candidate.path("articles").get(0);
			var second = candidate.path("articles").get(1);
			var left = articles.get(first.path("id").asString());
			var right = articles.get(second.path("id").asString());
			var index = new NewsDuplicateIndex(fingerprint);
			var expected = UUID.fromString(first.path("cluster_id").asString());
			index.add(expected, fingerprint.profile(left.path("title").asString(), "", left.path("body").asString()),
				Instant.parse(first.path("published_at").asString().replace(' ', 'T')), first.path("publisher").asString());
			var actual = index.findBest(fingerprint.profile(right.path("title").asString(), "", right.path("body").asString()),
				Instant.parse(second.path("published_at").asString().replace(' ', 'T')), second.path("publisher").asString());
			// 상위 다섯 쌍은 실제 본문·계약·일정을 대조한 보도자료 재전송 표본이다.
			if (checked++ < 5) assertThat(actual.targetClusterId()).isEqualTo(expected);
			if (actual.targetClusterId() != null) {
				copies++;
				System.out.printf("NEWS_DUPLICATE_COPY %s %s%n", left.path("id").asString(), right.path("id").asString());
			}
		}
		System.out.printf("NEWS_DUPLICATE_AUDIT reviewedCandidates=%d copies=%d%n", checked, copies);
	}
}
