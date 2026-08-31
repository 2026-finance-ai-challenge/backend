package com.kmarket.navigator.backend.news.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.news.domain.NewsStockMapping;

@Component
public class NewsStockMatcher {
	private static final List<String> FINANCIAL_CONTEXT = List.of(
		"주가", "증권", "주식", "상장", "코스피", "코스닥", "공시", "배당", "실적",
		"매출", "영업이익", "순이익", "시가총액", "목표가", "투자", "수주", "사업",
		"경영", "인수", "합병", "지분", "생산", "공급", "반도체", "배터리", "금융",
		"stock", "share", "listed", "kospi", "kosdaq", "dividend", "earnings",
		"revenue", "profit", "investment", "acquisition", "merger", "stake"
	);
	private static final List<String> STRONG_FINANCIAL_CONTEXT = List.of(
		"주가", "증권", "주식", "상장", "코스피", "코스닥", "공시", "배당", "실적",
		"매출", "영업이익", "순이익", "시가총액", "목표가", "stock", "share",
		"listed", "kospi", "kosdaq", "dividend", "earnings", "revenue", "profit"
	);
	private static final List<String> SPORTS_CONTEXT = List.of(
		"야구", "축구", "농구", "배구", "선수", "감독", "투수", "타자", "경기", "시즌",
		"홈런", "지명할당", "fa 선택", "복귀", "승리", "패배", "kbo", "mlb", "soccer",
		"football", "baseball", "basketball", "player", "coach", "pitcher"
	);

	public Map<String, BigDecimal> match(
		String text,
		Iterable<NewsStockMapping> mappings
	) {
		return matchText(text, mappings);
	}

	public Map<String, BigDecimal> matchArticle(
		String title,
		String excerpt,
		Iterable<NewsStockMapping> mappings
	) {
		List<NewsStockMapping> mappingList = new ArrayList<>();
		mappings.forEach(mappingList::add);
		Map<String, BigDecimal> matches = new LinkedHashMap<>(matchText(title, mappingList));
		Map<String, BigDecimal> excerptMatches = matchText(excerpt, mappingList);
		for (NewsStockMapping mapping : mappingList) {
			BigDecimal confidence = excerptMatches.get(mapping.stockCode());
			if (confidence != null && isUnambiguous(mapping)) {
				matches.merge(mapping.stockCode(), confidence, BigDecimal::max);
			}
		}
		String articleText = ((title == null ? "" : title) + " "
			+ (excerpt == null ? "" : excerpt)).toLowerCase(Locale.ROOT);
		matches.entrySet().removeIf(match -> mappingList.stream()
			.filter(mapping -> mapping.stockCode().equals(match.getKey()))
			.findFirst()
			.filter(mapping -> !isUnambiguous(mapping))
			.filter(mapping -> !hasFinancialContext(articleText))
			.isPresent());
		return Map.copyOf(matches);
	}

	private Map<String, BigDecimal> matchText(
		String text,
		Iterable<NewsStockMapping> mappings
	) {
		String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
		List<Evidence> evidence = new ArrayList<>();
		for (NewsStockMapping mapping : mappings) {
			addEvidence(evidence, lower, mapping.stockCode(), mapping.stockCode(), new BigDecimal("0.99"));
			addEvidence(evidence, lower, mapping.stockCode(), mapping.nameKo(), new BigDecimal("0.95"));
			addEvidence(evidence, lower, mapping.stockCode(), mapping.nameEn(), new BigDecimal("0.95"));
			for (String alias : mapping.aliases()) {
				addEvidence(evidence, lower, mapping.stockCode(), alias, new BigDecimal("0.90"));
			}
			addGroupEvidence(evidence, lower, mapping);
		}
		List<Evidence> specific = evidence.stream()
			.filter(candidate -> evidence.stream().noneMatch(other -> other.moreSpecificThan(candidate)))
			.sorted(Comparator.comparing(Evidence::start).thenComparing(Evidence::stockCode))
			.toList();
		Map<String, BigDecimal> matches = new LinkedHashMap<>();
		for (Evidence candidate : specific) {
			matches.merge(candidate.stockCode(), candidate.confidence(), BigDecimal::max);
		}
		return Map.copyOf(matches);
	}

	private boolean isUnambiguous(NewsStockMapping mapping) {
		String nameKo = mapping.nameKo() == null ? "" : mapping.nameKo().strip();
		long koreanLetters = nameKo.codePoints().filter(this::isKorean).count();
		if (koreanLetters > 0) {
			return koreanLetters >= 3;
		}
		long asciiLetters = nameKo.codePoints()
			.filter(codePoint -> codePoint < 128 && Character.isLetter(codePoint))
			.count();
		return asciiLetters >= 4;
	}

	private boolean hasFinancialContext(String text) {
		boolean sports = SPORTS_CONTEXT.stream().anyMatch(text::contains);
		List<String> required = sports ? STRONG_FINANCIAL_CONTEXT : FINANCIAL_CONTEXT;
		return required.stream().anyMatch(text::contains);
	}

	private void addEvidence(
		List<Evidence> evidence,
		String text,
		String stockCode,
		String rawTerm,
		BigDecimal confidence
	) {
		String term = rawTerm == null ? "" : rawTerm.strip().toLowerCase(Locale.ROOT);
		if (term.length() < 2) {
			return;
		}
		Set<Integer> starts = new HashSet<>();
		int from = 0;
		while (from < text.length()) {
			int start = text.indexOf(term, from);
			if (start < 0) {
				break;
			}
			int end = start + term.length();
			if (starts.add(start) && hasValidBoundary(text, term, start, end)) {
				evidence.add(new Evidence(stockCode, start, end, confidence));
			}
			from = start + 1;
		}
	}

	private void addGroupEvidence(List<Evidence> evidence, String text, NewsStockMapping mapping) {
		String name = mapping.nameKo();
		if (name == null || name.length() > 3) {
			return;
		}
		addEvidence(evidence, text, mapping.stockCode(), name + "그룹", new BigDecimal("0.93"));
	}

	private boolean hasValidBoundary(String text, String term, int start, int end) {
		boolean containsAscii = term.codePoints()
			.anyMatch(codePoint -> codePoint < 128 && Character.isLetter(codePoint));
		boolean containsKorean = term.codePoints().anyMatch(this::isKorean);
		boolean numeric = term.codePoints().allMatch(Character::isDigit);
		if ((containsAscii && !containsKorean) || numeric) {
			return boundaryBefore(text, start) && boundaryAfter(text, end);
		}
		long koreanLetters = term.codePoints().filter(this::isKorean).count();
		if (koreanLetters > 0) {
			return boundaryBefore(text, start)
				&& (boundaryAfter(text, end) || followedByStandaloneParticle(text, end));
		}
		return boundaryBefore(text, start)
			&& (boundaryAfter(text, end) || followedByStandaloneParticle(text, end));
	}

	private boolean boundaryBefore(String text, int index) {
		return index == 0 || !Character.isLetterOrDigit(text.codePointBefore(index));
	}

	private boolean boundaryAfter(String text, int index) {
		return index == text.length() || !Character.isLetterOrDigit(text.codePointAt(index));
	}

	private boolean followedByStandaloneParticle(String text, int index) {
		if (index >= text.length()) {
			return false;
		}
		int particle = text.codePointAt(index);
		if ("은는이가을를의와과도만로".indexOf(particle) < 0) {
			return false;
		}
		int afterParticle = index + Character.charCount(particle);
		return boundaryAfter(text, afterParticle);
	}

	private boolean isKorean(int codePoint) {
		return (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
			|| (codePoint >= 0x3131 && codePoint <= 0x318E);
	}

	private record Evidence(
		String stockCode,
		int start,
		int end,
		BigDecimal confidence
	) {
		boolean moreSpecificThan(Evidence candidate) {
			return !stockCode.equals(candidate.stockCode)
				&& start <= candidate.start
				&& end >= candidate.end
				&& (end - start) > (candidate.end - candidate.start);
		}
	}
}
