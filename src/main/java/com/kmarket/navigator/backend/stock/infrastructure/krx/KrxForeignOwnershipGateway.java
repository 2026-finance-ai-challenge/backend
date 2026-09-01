package com.kmarket.navigator.backend.stock.infrastructure.krx;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.stock.application.port.ForeignOwnershipGateway;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipCollectionTarget;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class KrxForeignOwnershipGateway implements ForeignOwnershipGateway {

	private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
	private static final String DATA_PATH = "/comm/bldAttendant/getJsonData.cmd";
	private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter SLASH_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
	private final RestClient restClient;
	private final KrxForeignOwnershipProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock = Clock.system(KOREA_ZONE);
	private final AtomicReference<Map<String, String>> cookies = new AtomicReference<>(Map.of());

	KrxForeignOwnershipGateway(
		@Qualifier("krxForeignOwnershipRestClient") RestClient restClient,
		KrxForeignOwnershipProperties properties,
		ObjectMapper objectMapper
	) {
		this.restClient = restClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	@Override
	public synchronized List<ForeignOwnershipSnapshot> fetchHistory(
		ForeignOwnershipCollectionTarget target,
		LocalDate from,
		LocalDate to
	) {
		if (!configured() || target.isinCode() == null || target.isinCode().isBlank()) {
			return List.of();
		}
		ensureLoggedIn();
		JsonNode root = requestHistory(target.isinCode(), from, to);
		JsonNode output = root.path("output");
		if (!output.isArray()) {
			return List.of();
		}
		List<ForeignOwnershipSnapshot> snapshots = new ArrayList<>();
		for (JsonNode row : output) {
			ForeignOwnershipSnapshot snapshot = map(row);
			if (snapshot != null
				&& !snapshot.baseDate().isBefore(from)
				&& !snapshot.baseDate().isAfter(to)) {
				snapshots.add(snapshot);
			}
		}
		return List.copyOf(snapshots);
	}

	private void ensureLoggedIn() {
		if (!cookies.get().isEmpty()) {
			return;
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("mbrId", properties.getMemberId());
		form.add("pw", properties.getPassword());
		form.add("site", "mdc");
		JsonNode response = restClient.post()
			.uri(properties.getLoginPath())
			.headers(this::applyHeaders)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.exchange((request, result) -> {
				ensureSuccess(result);
				rememberCookies(result.getHeaders());
				return parse(readLimited(result.getBody()));
			});
		if (cookies.get().isEmpty() || rejected(response)) {
			cookies.set(Map.of());
			throw new IllegalStateException("KRX login failed");
		}
	}

	private JsonNode requestHistory(String isinCode, LocalDate from, LocalDate to) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("bld", properties.getHistoryBld());
		form.add("locale", "ko_KR");
		form.add("searchType", "2");
		form.add("strtDd", BASIC_DATE.format(from));
		form.add("endDd", BASIC_DATE.format(to));
		form.add("isuCd", isinCode);
		JsonNode response = restClient.post()
			.uri(DATA_PATH)
			.headers(this::applyHeaders)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.exchange((request, result) -> {
				ensureSuccess(result);
				rememberCookies(result.getHeaders());
				return parse(readLimited(result.getBody()));
			});
		if (rejected(response)) {
			cookies.set(Map.of());
			throw new IllegalStateException("KRX session expired");
		}
		return response;
	}

	private void applyHeaders(HttpHeaders headers) {
		headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
		headers.set(HttpHeaders.REFERER, properties.getBaseUrl()
			+ "/contents/MDC/MDI/outerLoader/index.cmd");
		headers.set("X-Requested-With", "XMLHttpRequest");
		String cookieHeader = cookieHeader();
		if (!cookieHeader.isBlank()) {
			headers.set(HttpHeaders.COOKIE, cookieHeader);
		}
	}

	private void rememberCookies(HttpHeaders headers) {
		List<String> values = headers.get(HttpHeaders.SET_COOKIE);
		if (values == null) {
			return;
		}
		Map<String, String> updated = new LinkedHashMap<>(cookies.get());
		for (String value : values) {
			String pair = value.split(";", 2)[0];
			int separator = pair.indexOf('=');
			if (separator > 0) {
				updated.put(pair.substring(0, separator), pair.substring(separator + 1));
			}
		}
		cookies.set(Map.copyOf(updated));
	}

	private String cookieHeader() {
		return cookies.get().entrySet().stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.collect(java.util.stream.Collectors.joining("; "));
	}

	private ForeignOwnershipSnapshot map(JsonNode row) {
		Long owned = longValue(row, "FORN_HD_QTY");
		LocalDate baseDate = date(row, "TRD_DD");
		if (owned == null || baseDate == null) {
			return null;
		}
		Long limit = longValue(row, "FORN_ORD_LMT_QTY");
		Long available = limit == null ? null : Math.max(0, limit - owned);
		return new ForeignOwnershipSnapshot(
			owned,
			longValue(row, "LIST_SHRS"),
			limit,
			available,
			decimal(row, "FORN_SHR_RT"),
			decimal(row, "FORN_LMT_EXHST_RT"),
			baseDate,
			clock.instant(),
			"KRX_DATA_SYSTEM"
		);
	}

	private static void ensureSuccess(org.springframework.http.client.ClientHttpResponse response) {
		try {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new IllegalStateException("KRX returned an unsuccessful status");
			}
		} catch (IOException exception) {
			throw new IllegalStateException("KRX status could not be read", exception);
		}
	}

	private static boolean rejected(JsonNode response) {
		String resultCode = text(response, "resultCode");
		String code = text(response, "code");
		return response.has("errorCode")
			|| response.has("loginErrCnt")
			|| "ERROR".equalsIgnoreCase(resultCode)
			|| "ERROR".equalsIgnoreCase(code);
	}

	private JsonNode parse(byte[] body) {
		try {
			return objectMapper.readTree(body);
		} catch (RuntimeException exception) {
			throw new IllegalStateException("KRX returned invalid JSON", exception);
		}
	}

	private static byte[] readLimited(java.io.InputStream input) {
		try {
			byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
			if (body.length > MAX_RESPONSE_BYTES) {
				throw new IllegalStateException("KRX response exceeds size limit");
			}
			return body;
		} catch (IOException exception) {
			throw new IllegalStateException("KRX response could not be read", exception);
		}
	}

	private static LocalDate date(JsonNode row, String field) {
		String value = text(row, field);
		if (value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value, value.contains("/") ? SLASH_DATE : BASIC_DATE);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static Long longValue(JsonNode row, String field) {
		String value = normalize(text(row, field));
		if (value.isBlank() || "-".equals(value)) {
			return null;
		}
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static BigDecimal decimal(JsonNode row, String field) {
		String value = normalize(text(row, field));
		if (value.isBlank() || "-".equals(value)) {
			return null;
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static String text(JsonNode row, String field) {
		if (!row.has(field) || row.path(field).isNull()) {
			return "";
		}
		String value = row.path(field).stringValue();
		return value == null ? "" : value.trim();
	}

	private static String normalize(String value) {
		return value.replace(",", "").replace("%", "").trim();
	}
}
