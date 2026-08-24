package com.kmarket.navigator.backend.disclosure.infrastructure.translation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.DisclosureTitlePolicy;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationCatalog;

@Component
class ResourceDisclosureTitleTranslationCatalog implements DisclosureTitleTranslationCatalog {

	private static final String RESOURCE = "disclosure/title-translations-v1.tsv";
	private static final Pattern HANGUL = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣ]");
	private static final Pattern MODIFIER = Pattern.compile("^\\[([^]]+)](.*)$");
	private static final Pattern REPORTING_PERIOD = Pattern.compile("^(.*?)[ ]*\\(([0-9]{4}\\.[0-9]{2})\\)$");
	private static final Pattern EMBEDDED_SUBSIDIARY_CONTEXT = Pattern.compile(
		"^(.*)\\((자회사의 주요경영사항|종속회사의주요경영사항)\\)[ ]*\\((.*)\\)$"
	);
	private static final Pattern BRACKET_QUALIFIER = Pattern.compile("^(.*)\\[([^\\[\\]]+)\\]$");
	private static final Pattern PARENTHETICAL_QUALIFIER = Pattern.compile("^(.*)\\(([^()]*)\\)$");
	private static final Map<String, String> MODIFIERS = Map.of(
		"기재정정", "Amended",
		"첨부추가", "Attachment Added",
		"첨부정정", "Attachment Amended",
		"발행조건확정", "Final Terms",
		"정정제출요구", "Correction Requested",
		"연장결정", "Extension Decision"
	);
	private static final Map<String, String> CONTEXT_SUFFIXES = Map.of(
		"(자회사의 주요경영사항)", "Material Management Matter of a Subsidiary",
		"(종속회사의주요경영사항)", "Material Management Matter of a Subsidiary",
		"(자율공시)", "Voluntary Disclosure",
		"(공정공시)", "Fair Disclosure",
		"(안내공시)", "Notice",
		"(자진공시)", "Voluntary Disclosure"
	);
	private static final Map<String, String> BRACKET_QUALIFIERS = Map.ofEntries(
		Map.entry("사채", "Bonds"),
		Map.entry("주식", "Shares"),
		Map.entry("파생결합증권", "Derivative-Linked Securities"),
		Map.entry("파생결합증권(주가연계증권)", "Derivative-Linked Securities (Equity-Linked Securities)"),
		Map.entry("파생결합증권(주식워런트증권)", "Derivative-Linked Securities (Equity Warrant Securities)"),
		Map.entry("파생결합증권의경우", "Derivative-Linked Securities"),
		Map.entry("파생결합증권(주가연계증권)의경우", "Derivative-Linked Securities (Equity-Linked Securities)"),
		Map.entry("파생결합증권(주식워런트증권)의경우", "Derivative-Linked Securities (Equity Warrant Securities)"),
		Map.entry("주식(모집설립제외)의경우", "Shares (Excluding Incorporation by Public Offering)"),
		Map.entry("무보증사채의경우", "Unsecured Bonds"),
		Map.entry("보증사채또는담보부사채의경우", "Guaranteed or Secured Bonds"),
		Map.entry("모집설립이외", "Other Than Incorporation by Public Offering"),
		Map.entry("기타유가증권", "Other Securities"),
		Map.entry("보험", "Insurance"),
		Map.entry("분기별공시(개별회사용)", "Quarterly Disclosure (Individual Company)"),
		Map.entry("분기별공시(대표회사용)", "Quarterly Disclosure (Representative Company)"),
		Map.entry("신탁회사(은행신탁계정포함)", "Trust Company (Including Bank Trust Accounts)"),
		Map.entry("연1회(동일인용)", "Annual Disclosure (Controlling Person)"),
		Map.entry("연1회공시및1/4분기용(개별회사)", "Annual and First-Quarter Disclosure (Individual Company)"),
		Map.entry("연1회공시및1/4분기용(대표회사)", "Annual and First-Quarter Disclosure (Representative Company)"),
		Map.entry("유가증권-수익증권", "Securities — Beneficiary Certificates"),
		Map.entry("유가증권-채권", "Securities — Bonds"),
		Map.entry("유가증권-주식", "Securities — Shares"),
		Map.entry("일괄신고-사채", "Shelf Registration — Bonds"),
		Map.entry("일괄신고-파생결합증권", "Shelf Registration — Derivative-Linked Securities"),
		Map.entry("일괄신고-파생결합증권(주가연계증권)", "Shelf Registration — Equity-Linked Securities"),
		Map.entry("일괄신고-파생결합증권(주식워런트증권)", "Shelf Registration — Equity Warrant Securities"),
		Map.entry("일반현황(개별회사)", "General Status (Individual Company)"),
		Map.entry("일반현황(대표회사)", "General Status (Representative Company)"),
		Map.entry("장단기차입", "Short- and Long-Term Borrowings"),
		Map.entry("장단기대여", "Short- and Long-Term Loans"),
		Map.entry("파생금융상품", "Derivative Financial Instruments"),
		Map.entry("예ㆍ적금", "Deposits and Savings"),
		Map.entry("CP", "Commercial Paper")
	);
	private static final Map<String, String> PARENTHETICAL_QUALIFIERS = Map.ofEntries(
		Map.entry("일괄신고", "Shelf Registration"),
		Map.entry("채무증권", "Debt Securities"),
		Map.entry("지분증권", "Equity Securities"),
		Map.entry("기타파생결합증권", "Other Derivative-Linked Securities"),
		Map.entry("기타파생결합사채", "Other Derivative-Linked Bonds"),
		Map.entry("파생결합증권-주가연계증권", "Derivative-Linked Securities — Equity-Linked Securities"),
		Map.entry("파생결합증권-주식워런트증권", "Derivative-Linked Securities — Equity Warrant Securities"),
		Map.entry("파생결합증권-상장지수증권", "Derivative-Linked Securities — Exchange-Traded Notes"),
		Map.entry("파생결합사채-주가연계파생결합사채", "Derivative-Linked Bonds — Equity-Linked Bonds"),
		Map.entry("합병", "Merger"),
		Map.entry("분할", "Spin-Off"),
		Map.entry("주식의포괄적교환ㆍ이전", "Comprehensive Share Exchange or Transfer"),
		Map.entry("미확정", "Unconfirmed"),
		Map.entry("담보제공포함", "Including Collateral Provision"),
		Map.entry("일정금액이상의청구", "Claim Above the Reporting Threshold"),
		Map.entry("자율공시:일정금액미만의청구", "Voluntary Disclosure: Claim Below the Reporting Threshold"),
		Map.entry("자기주식처분결정", "Decision to Dispose of Treasury Shares"),
		Map.entry("자기주식취득결정", "Decision to Acquire Treasury Shares"),
		Map.entry("자기주식취득신탁계약체결결정", "Decision to Enter into a Treasury Share Trust Agreement"),
		Map.entry("자기주식취득신탁계약해지결정", "Decision to Terminate a Treasury Share Trust Agreement"),
		Map.entry("전환사채권발행결정", "Decision to Issue Convertible Bonds"),
		Map.entry("주식교환ㆍ이전결정", "Decision on Share Exchange or Transfer"),
		Map.entry("중요한자산양수도결정", "Decision on Transfer or Acquisition of Material Assets"),
		Map.entry("타법인주식및출자증권양도결정", "Decision to Transfer Shares or Equity Securities of Another Company"),
		Map.entry("타법인주식및출자증권양수결정", "Decision to Acquire Shares or Equity Securities of Another Company"),
		Map.entry("회사분할결정", "Decision on Company Spin-Off"),
		Map.entry("회사분할합병결정", "Decision on Split-Off Merger"),
		Map.entry("회사합병결정", "Decision on Company Merger"),
		Map.entry("상각형조건부자본증권발행결정", "Decision to Issue Write-Down Contingent Capital Securities"),
		Map.entry("영업양수결정", "Decision to Acquire a Business"),
		Map.entry("영업정지", "Business Suspension"),
		Map.entry("주식의포괄적교환ㆍ이전결정", "Decision on Comprehensive Share Exchange or Transfer"),
		Map.entry("풋백옵션등계약체결결정", "Decision to Enter into a Put-Back Option or Similar Agreement"),
		Map.entry("합병결정", "Merger Decision"),
		Map.entry("SK에너지", "SK Energy"),
		Map.entry("SK온", "SK On"),
		Map.entry("두산에너빌리티", "Doosan Enerbility"),
		Map.entry("하나은행", "Hana Bank"),
		Map.entry("한국조선해양", "Korea Shipbuilding & Offshore Engineering"),
		Map.entry("현대삼호중공업", "Hyundai Samho Heavy Industries"),
		Map.entry("현대오일뱅크", "Hyundai Oilbank"),
		Map.entry("현대제뉴인", "HD Hyundai XiteSolution"),
		Map.entry("현대중공업주식회사", "Hyundai Heavy Industries Co., Ltd."),
		Map.entry("무상증자", "Bonus Issue"),
		Map.entry("유상증자", "Capital Increase with Consideration"),
		Map.entry("연차보고서", "Annual Report")
	);
	private final Map<String, String> translations;

	ResourceDisclosureTitleTranslationCatalog() {
		this.translations = load();
	}

	@Override
	public Optional<String> translate(String normalizedTitle) {
		return translateNormalized(DisclosureTitlePolicy.normalize(normalizedTitle));
	}

	@Override
	public String reviewKey(String normalizedTitle) {
		String current = DisclosureTitlePolicy.normalize(normalizedTitle);
		Optional<String> reduced;
		while ((reduced = reduceComposite(current)).isPresent()) {
			current = reduced.orElseThrow();
		}
		return current;
	}

	private Optional<String> translateNormalized(String title) {
		String exact = translations.get(title);
		if (exact != null) {
			return Optional.of(exact);
		}

		var modifierMatcher = MODIFIER.matcher(title);
		if (modifierMatcher.matches()) {
			String modifier = MODIFIERS.get(modifierMatcher.group(1));
			if (modifier != null) {
				return translateNormalized(modifierMatcher.group(2).strip())
					.map(value -> "[" + modifier + "] " + value);
			}
		}

		var periodMatcher = REPORTING_PERIOD.matcher(title);
		if (periodMatcher.matches()) {
			return translateNormalized(periodMatcher.group(1))
				.map(value -> value + " (" + periodMatcher.group(2) + ")");
		}

		var subsidiaryMatcher = EMBEDDED_SUBSIDIARY_CONTEXT.matcher(title);
		if (subsidiaryMatcher.matches()) {
			String base = subsidiaryMatcher.group(1).strip() + "(" + subsidiaryMatcher.group(3).strip() + ")";
			return translateNormalized(base)
				.map(value -> value + " (Material Management Matter of a Subsidiary)");
		}

		for (var suffix : CONTEXT_SUFFIXES.entrySet()) {
			if (title.endsWith(suffix.getKey())) {
				String base = title.substring(0, title.length() - suffix.getKey().length()).strip();
				return translateNormalized(base)
					.map(value -> value + " (" + suffix.getValue() + ")");
			}
		}

		var bracketMatcher = BRACKET_QUALIFIER.matcher(title);
		if (bracketMatcher.matches()) {
			String qualifier = BRACKET_QUALIFIERS.get(bracketMatcher.group(2));
			if (qualifier != null) {
				return translateNormalized(bracketMatcher.group(1).strip())
					.map(value -> value + " [" + qualifier + "]");
			}
		}

		var parentheticalMatcher = PARENTHETICAL_QUALIFIER.matcher(title);
		if (parentheticalMatcher.matches()) {
			String qualifier = PARENTHETICAL_QUALIFIERS.get(parentheticalMatcher.group(2));
			if (qualifier != null) {
				return translateNormalized(parentheticalMatcher.group(1).strip())
					.map(value -> value + " (" + qualifier + ")");
			}
		}
		return Optional.empty();
	}

	private Optional<String> reduceComposite(String title) {
		var modifierMatcher = MODIFIER.matcher(title);
		if (modifierMatcher.matches() && MODIFIERS.containsKey(modifierMatcher.group(1))) {
			return Optional.of(modifierMatcher.group(2).strip());
		}
		var periodMatcher = REPORTING_PERIOD.matcher(title);
		if (periodMatcher.matches()) {
			return Optional.of(periodMatcher.group(1).strip());
		}
		var subsidiaryMatcher = EMBEDDED_SUBSIDIARY_CONTEXT.matcher(title);
		if (subsidiaryMatcher.matches()) {
			return Optional.of(
				subsidiaryMatcher.group(1).strip() + "(" + subsidiaryMatcher.group(3).strip() + ")"
			);
		}
		for (String suffix : CONTEXT_SUFFIXES.keySet()) {
			if (title.endsWith(suffix)) {
				return Optional.of(title.substring(0, title.length() - suffix.length()).strip());
			}
		}
		var bracketMatcher = BRACKET_QUALIFIER.matcher(title);
		if (bracketMatcher.matches() && BRACKET_QUALIFIERS.containsKey(bracketMatcher.group(2))) {
			return Optional.of(bracketMatcher.group(1).strip());
		}
		var parentheticalMatcher = PARENTHETICAL_QUALIFIER.matcher(title);
		if (parentheticalMatcher.matches()
			&& PARENTHETICAL_QUALIFIERS.containsKey(parentheticalMatcher.group(2))) {
			return Optional.of(parentheticalMatcher.group(1).strip());
		}
		return Optional.empty();
	}

	private static Map<String, String> load() {
		Map<String, String> loaded = new LinkedHashMap<>();
		ClassPathResource resource = new ClassPathResource(RESOURCE);
		try (var reader = new BufferedReader(new InputStreamReader(
			resource.getInputStream(),
			StandardCharsets.UTF_8
		))) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (lineNumber == 1 && line.equals("source_ko\ttitle_en")) {
					continue;
				}
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] columns = line.split("\\t", -1);
				if (columns.length != 2) {
					throw invalid(lineNumber, "expected two tab-separated columns");
				}
				String source = DisclosureTitlePolicy.normalize(columns[0]);
				String translation = columns[1].strip();
				if (translation.isEmpty() || HANGUL.matcher(translation).find()) {
					throw invalid(lineNumber, "translation must be non-empty English text");
				}
				if (loaded.putIfAbsent(source, translation) != null) {
					throw invalid(lineNumber, "duplicate source title");
				}
			}
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to load disclosure title catalog", exception);
		}
		return Map.copyOf(loaded);
	}

	private static IllegalStateException invalid(int lineNumber, String reason) {
		return new IllegalStateException(RESOURCE + ":" + lineNumber + " " + reason);
	}
}
