package com.kmarket.navigator.backend.disclosure.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisclosureReceiverTests {
	private DisclosureReceiver receiver(String html) {
		return DisclosureReceiver.from(List.of(new DisclosureDocument(UUID.randomUUID(), "test.html", 1, "hash", html, List.of())));
	}

	@Test
	void extractsOnlyExplicitRecipients() {
		assertThat(receiver("<table><tr><td>금융위원회 / 한국거래소 귀중</td></tr></table>").en())
			.isEqualTo("Financial Services Commission / Korea Exchange");
		assertThat(receiver("<p>금융감독원 귀중</p>").ko()).isEqualTo("금융감독원");
		assertThat(receiver("<table><tr><td>금융감독원장 귀하</td></tr></table>").en())
			.isEqualTo("Financial Supervisory Service");
		assertThat(receiver("<p>회사는 금융위원회의 규정을 준수합니다.</p>").ko()).isNull();
		assertThat(receiver(null).en()).isNull();
	}
}
