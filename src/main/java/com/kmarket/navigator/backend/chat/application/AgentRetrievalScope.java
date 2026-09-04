package com.kmarket.navigator.backend.chat.application;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.kmarket.navigator.backend.stock.domain.StockIdentity;

record AgentRetrievalScope(List<StockIdentity> stocks, boolean news, boolean filings,
	boolean financials, LocalDate from, LocalDate to, boolean includeLatest, boolean unknownSymbol) {

	private static final Pattern SYMBOL = Pattern.compile("\\b(?=[A-Z0-9]*[0-9])[A-Z0-9]{6}\\b");
	private static final Pattern NUMERIC_MONTH = Pattern.compile("(20\\d{2})\\s*(?:년|[-/])\\s*(1[0-2]|0?[1-9])(?:월|\\b)");
	private static final Pattern NEWS = Pattern.compile("\\b(news|articles?|headlines?)\\b|뉴스|기사|소식", Pattern.CASE_INSENSITIVE);
	private static final Pattern FILINGS = Pattern.compile("\\b(filings?|disclosures?|dart)\\b|공시|보고서", Pattern.CASE_INSENSITIVE);
	private static final Pattern FINANCIALS = Pattern.compile(
		"\\b(revenues?|sales|earnings?|profits?|operating profit|net income|financial results?)\\b|매출|영업이익|순이익|이익|실적|재무",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern LATEST = Pattern.compile("\\b(latest|recent|current|today)\\b|최신|최근|오늘", Pattern.CASE_INSENSITIVE);

	static AgentRetrievalScope parse(String question, List<StockIdentity> supported) {
		return parse(question, supported, Map.of());
	}

	static AgentRetrievalScope parse(String question, List<StockIdentity> supported, Map<String, List<String>> aliases) {
		String normalized = normalize(question);
		List<StockIdentity> matched = supported.stream().filter(stock -> matchesCode(question, stock)
			|| matchesName(question, normalized, stock)).filter(stock -> matchesCode(question, stock)
			|| supported.stream().noneMatch(other -> !other.stockCode().equals(stock.stockCode())
				&& matchesName(question, normalized, other) && extendsName(other, stock))).limit(2).toList();
		// 정식 회사명 우선으로 계열사를 구분하고, 미일치 때만 검증된 별칭·제한된 오타를 허용한다.
		if (matched.isEmpty()) matched = supported.stream().filter(stock -> fuzzyEnglishName(normalized, stock.nameEn())).limit(2).toList();
		if (matched.isEmpty()) matched = supported.stream().filter(stock -> aliases.getOrDefault(stock.stockCode(), List.of())
			.stream().anyMatch(alias -> (" " + normalized + " ").contains(" " + normalize(alias) + " "))).limit(2).toList();
		YearMonth month = requestedMonth(question);
		return new AgentRetrievalScope(matched, NEWS.matcher(question).find(), FILINGS.matcher(question).find(), FINANCIALS.matcher(question).find(),
			month == null ? null : month.atDay(1), month == null ? null : month.atEndOfMonth(),
			month == null || LATEST.matcher(question).find(),
			matched.isEmpty() && SYMBOL.matcher(question.toUpperCase(Locale.ROOT)).find());
	}

	private static boolean matchesCode(String question, StockIdentity stock) {
		return Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(stock.stockCode()) + "(?![A-Z0-9])")
			.matcher(question.toUpperCase(Locale.ROOT)).find();
	}

	private static boolean matchesName(String question, String normalized, StockIdentity stock) {
		return stock.nameKo() != null && !stock.nameKo().isBlank() && question.contains(stock.nameKo())
			|| stock.nameEn() != null && matchesEnglishName(normalized, stock.nameEn());
	}

	private static boolean extendsName(StockIdentity longer, StockIdentity shorter) {
		return longer.nameKo() != null && shorter.nameKo() != null
			&& longer.nameKo().length() > shorter.nameKo().length() && longer.nameKo().contains(shorter.nameKo())
			|| longer.nameEn() != null && shorter.nameEn() != null
			&& cleanName(longer.nameEn()).length() > cleanName(shorter.nameEn()).length()
			&& matchesEnglishName(cleanName(longer.nameEn()), shorter.nameEn());
	}

	private static boolean matchesEnglishName(String question, String officialName) {
		String name = cleanName(officialName);
		return !name.isBlank() && (" " + question + " ").contains(" " + name + " ");
	}

	private static String cleanName(String name) {
		return normalize(name.replaceAll("(?i)\\b(co|corp|corporation|inc|ltd|limited)\\b\\.?", " "));
	}

	private static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("['’]s\\b", "")
			.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
	}

	private static boolean fuzzyEnglishName(String question, String officialName) {
		if (officialName == null) return false;
		String[] name = cleanName(officialName).split(" ");
		String[] words = question.split(" ");
		if (name.length < 2 || name[0].length() < 3) return false;
		for (int offset = 0; offset + name.length <= words.length; offset++) {
			if (!words[offset].equals(name[0])) continue;
			boolean match = true;
			for (int i = 1; i < name.length; i++) {
				if (!words[offset + i].equals(name[i]) && (name[i].length() < 7 || distance(words[offset + i], name[i]) > 2)) {
					match = false; break;
				}
			}
			if (match) return true;
		}
		return false;
	}

	private static int distance(String a, String b) {
		if (Math.abs(a.length() - b.length()) > 2) return 3;
		int[] row = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) row[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			int previous = row[0]; row[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int old = row[j];
				row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), previous + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
				previous = old;
			}
		}
		return row[b.length()];
	}

	private static YearMonth requestedMonth(String question) {
		var numeric = NUMERIC_MONTH.matcher(question);
		if (numeric.find()) {
			try { return YearMonth.of(Integer.parseInt(numeric.group(1)), Integer.parseInt(numeric.group(2))); }
			catch (DateTimeException ignored) { return null; }
		}
		for (Month month : Month.values()) {
			String full = month.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
			String shortName = month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
			var english = Pattern.compile("(?i)\\b(?:" + full + "|" + shortName + ")\\s+(20\\d{2})\\b").matcher(question);
			if (english.find()) return YearMonth.of(Integer.parseInt(english.group(1)), month);
		}
		return null;
	}
}
