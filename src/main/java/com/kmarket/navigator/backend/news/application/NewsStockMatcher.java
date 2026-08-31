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

	public Map<String, BigDecimal> match(
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
		if (koreanLetters > 2) {
			return true;
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
