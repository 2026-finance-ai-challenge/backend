package com.kmarket.navigator.backend.disclosure.domain;

import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

public record DisclosureReceiver(String ko, String en) {

	public static DisclosureReceiver from(List<DisclosureDocument> documents) {
		var names = new java.util.LinkedHashMap<String, String>();
		names.put("금융위원회", "Financial Services Commission");
		names.put("한국거래소", "Korea Exchange");
		names.put("금융감독원", "Financial Supervisory Service");
		var recipients = new java.util.LinkedHashSet<String>();
		for (var document : documents) {
			if (document.originalHtml() == null) continue;
			String text = Jsoup.parse(document.originalHtml()).text().replaceAll("\\s+", "");
			String header = text.substring(0, Math.min(text.length(), 1500));
			// 본문에 등장하는 기관명이 아니라 수신처 표기만 사용한다.
			var matcher = Pattern.compile(
				"((?:(?:금융위원회|한국거래소|금융감독원)(?:위원장|장)?[/·,및]*)+)(?:귀중|귀하)"
			).matcher(header);
			while (matcher.find()) {
				for (String name : names.keySet()) if (matcher.group(1).contains(name)) recipients.add(name);
			}
		}
		if (recipients.isEmpty()) return new DisclosureReceiver(null, null);
		return new DisclosureReceiver(String.join(" / ", recipients),
			recipients.stream().map(names::get).collect(java.util.stream.Collectors.joining(" / ")));
	}
}
