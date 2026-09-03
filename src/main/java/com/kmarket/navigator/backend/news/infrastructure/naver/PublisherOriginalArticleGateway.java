package com.kmarket.navigator.backend.news.infrastructure.naver;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.news.application.port.NewsOriginalArticleGateway;
import com.kmarket.navigator.backend.news.domain.OriginalNewsArticle;

@Component
class PublisherOriginalArticleGateway implements NewsOriginalArticleGateway {

	private static final Logger log = LoggerFactory.getLogger(PublisherOriginalArticleGateway.class);
	private static final String SOURCE_POLICY = "publisher_public_article_v2";
	private static final int MAX_REDIRECTS = 5;
	private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
	private static final int MAX_CONTENT_CHARS = 120_000;
	private static final int MIN_BODY_CHARS = 100;
	private static final Pattern SCRIPT_REDIRECT = Pattern.compile(
		"(?:top\\.|window\\.|document\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]",
		Pattern.CASE_INSENSITIVE
	);
	private static final List<String> BODY_SELECTORS = List.of(
		"#DivArticleContent", "#articleText", "#jose_news_view", "#divNewsContent", "#textBody", "#article-view-content-div", "[itemprop=articleBody]",
		".article-body-only", "#articleBody", "#article_body", "#CmAdContent", ".acem_text",
		"#article", "#article_main", "#cont_newstext", ".detail-body", ".news_body",
		".news-content", ".article_body", ".article-body", "#news_body", ".article_content",
		".article-content", ".article_txt", ".view_content", ".news_content", ".newsct_article", "#dic_area",
		".go_trans._article_content", "main article", "article", "main"
	);
	private static final String NON_CONTENT_SELECTOR = String.join(",",
		"script", "style", "noscript", "iframe", "figure", "figcaption", "form", "button",
		"header", "footer", "nav", "aside", "table", "[hidden]", ".ad", ".ads", ".advertisement",
		"[class*=advert]", "[id*=advert]", "[class*=banner]", "[id*=banner]",
		"[class*=share]", "[id*=share]", "[class*=sns]", "[id*=sns]",
		"[class*=recommend]", "[id*=recommend]", "[class*=related]", "[id*=related]",
		"[class*=ranking]", "[id*=ranking]", "[class*=popular]", "[id*=popular]",
		"[class*=subscribe]", "[id*=subscribe]", "[class*=promotion]", "[id*=promotion]",
		"[class*=copyright]", "[id*=copyright]", "[class*=reporter]", "[id*=reporter]",
		"[class*=vote]", "[id*=vote]", "[class*=poll]", "[id*=poll]", ".teditor", ".list_news"
	);

	private final HttpClient httpClient;
	private final Duration readTimeout;

	@Autowired
	PublisherOriginalArticleGateway(NaverNewsProperties properties) {
		this(
			HttpClient.newBuilder()
				.connectTimeout(properties.getConnectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build(),
			properties.getReadTimeout()
		);
	}

	PublisherOriginalArticleGateway(HttpClient httpClient, Duration readTimeout) {
		this.httpClient = httpClient;
		this.readTimeout = readTimeout;
	}

	@Override
	public Optional<OriginalNewsArticle> fetch(String originalUrl) {
		URI uri = safeHttpUri(originalUrl);
		if (uri == null) {
			return Optional.empty();
		}
		try {
			long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
			FetchedHtml fetched = fetchHtml(uri, 0, deadline);
			Optional<OriginalNewsArticle> parsed = parse(fetched);
			if (parsed.isPresent()) {
				return parsed;
			}
			for (URI alternate : alternateUris(fetched)) {
				Optional<OriginalNewsArticle> alternateArticle = parse(fetchHtml(alternate, 0, deadline));
				if (alternateArticle.isPresent()) {
					return alternateArticle;
				}
			}
		} catch (IOException exception) {
			log.warn("Original news response failed host={} type={}", uri.getHost(), exception.getClass().getSimpleName());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("Original news request interrupted host={}", uri.getHost());
		} catch (RuntimeException exception) {
			log.warn("Original news fetch failed host={} type={}", uri.getHost(), exception.getClass().getSimpleName());
		}
		return Optional.empty();
	}

	private FetchedHtml fetchHtml(URI uri, int redirectCount, long deadline) throws IOException, InterruptedException {
		if (safeHttpUri(uri.toString()) == null) {
			throw new IllegalArgumentException("unsafe news URL");
		}
		long remaining = Math.min(readTimeout.toNanos(), deadline - System.nanoTime());
		if (remaining <= 0) throw new java.net.http.HttpTimeoutException("Article fetch deadline exceeded");
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofNanos(remaining))
			.header("User-Agent", "K-Market-Navigator/1.0")
			.header("Accept", "text/html,application/xhtml+xml;q=0.9")
			.header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8")
			.GET()
			.build();
		// 헤더뿐 아니라 본문 수신 시간·크기도 제한해 느린 언론사가 수집 슬롯을 점유하지 않게 한다.
		var pending = httpClient.sendAsync(request,
			HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
		HttpResponse<byte[]> response;
		try { response = pending.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS); }
		catch (java.util.concurrent.TimeoutException exception) {
			pending.cancel(true);
			throw new java.net.http.HttpTimeoutException("Article body deadline exceeded");
		} catch (java.util.concurrent.ExecutionException exception) {
			throw new IOException("Article response failed", exception.getCause());
		} catch (InterruptedException exception) {
			pending.cancel(true);
			throw exception;
		}
			if (response.statusCode() >= 300 && response.statusCode() < 400) {
				if (redirectCount >= MAX_REDIRECTS) {
					throw new IllegalStateException("too many redirects");
				}
				String location = response.headers().firstValue("location").orElse("");
				URI redirected = safeHttpUri(uri.resolve(location).toString());
				if (redirected == null) {
					throw new IllegalArgumentException("unsafe redirect URL");
				}
				return fetchHtml(redirected, redirectCount + 1, deadline);
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("publisher rejected request");
			}
			String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
			if (!contentType.isBlank() && !contentType.contains("text/html")
				&& !contentType.contains("application/xhtml+xml")) {
				throw new IllegalStateException("publisher response is not HTML");
			}
			byte[] bytes = response.body();
			if (bytes.length > MAX_RESPONSE_BYTES) {
				throw new IllegalStateException("publisher response exceeds size limit");
			}
			return new FetchedHtml(decodeHtml(bytes, contentType, uri), uri);
	}

	String decodeHtml(byte[] bytes, String contentType, URI uri) throws IOException {
		var charset = Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE)
			.matcher(contentType);
		String declared = charset.find() ? charset.group(1) : null;
		if (declared != null && declared.equalsIgnoreCase("euc-kr")) declared = "MS949";
		// HTTP·HTML의 문자 인코딩을 읽어 한글 원문이 대체 문자로 손상되지 않게 한다.
		return Jsoup.parse(new ByteArrayInputStream(bytes), declared, uri.toString()).outerHtml();
	}

	private Optional<OriginalNewsArticle> parse(FetchedHtml fetched) {
		if (fetched.html().isBlank()) {
			return Optional.empty();
		}
		Document document = Jsoup.parse(fetched.html(), fetched.sourceUri().toString());
		String canonicalUrl = canonicalUrl(document, fetched.sourceUri());
		String thumbnailUrl = imageUrl(document, fetched.sourceUri());
		String selected = findArticleBody(document);
		if (selected.isBlank()) {
			return Optional.empty();
		}
		String body = selected.length() > MAX_CONTENT_CHARS ? selected.substring(0, MAX_CONTENT_CHARS) : selected;
		return Optional.of(new OriginalNewsArticle(body, canonicalUrl, thumbnailUrl, SOURCE_POLICY));
	}

	String findArticleBody(Document document) {
		for (String selector : BODY_SELECTORS) {
			String selected = "";
			var elements = document.select(selector);
			for (Element element : elements) {
				String candidate = cleanArticleText(element);
				if (isLikelyBody(candidate) && candidate.length() > selected.length()) {
					selected = candidate;
				}
			}
			// 구체적인 본문 선택자가 잡히면 뒤의 article/main 전체 영역으로 넓히지 않는다.
			if (!selected.isBlank()) {
				return selected;
			}
			// 본문이 비었거나 접근 안내인 경우 페이지 전체를 원문으로 대체하지 않는다.
			if (!elements.isEmpty() && !Set.of("main article", "article", "main").contains(selector)) return "";
		}
		return "";
	}

	private List<URI> alternateUris(FetchedHtml fetched) {
		Document document = Jsoup.parse(fetched.html(), fetched.sourceUri().toString());
		Set<URI> candidates = new LinkedHashSet<>();
		Element amp = document.selectFirst("link[rel=amphtml]");
		if (amp != null) {
			addCandidate(candidates, fetched.sourceUri(), amp.attr("href"));
		}
		for (Element script : document.select("script")) {
			Matcher matcher = SCRIPT_REDIRECT.matcher(script.html());
			while (matcher.find()) {
				addCandidate(candidates, fetched.sourceUri(), matcher.group(1));
			}
		}
		return new ArrayList<>(candidates).stream().limit(2).toList();
	}

	private void addCandidate(Set<URI> candidates, URI base, String raw) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		URI candidate = safeHttpUri(base.resolve(raw).toString());
		if (candidate != null && !candidate.equals(base)) {
			candidates.add(candidate);
		}
	}

	private String cleanArticleText(Element element) {
		Element copy = element.clone();
		copy.select(NON_CONTENT_SELECTOR).remove();
		// p 태그 밖의 본문과 br 문단도 보존한다. 부가 목록만 추출하는 경로를 없앤다.
		copy.select("p,div,li,blockquote,h1,h2,h3,h4").forEach(block -> {
			block.before("\n\n");
			block.appendText("\n\n");
		});
		String text = copy.wholeText();
		return normalizeArticleText(removeBoilerplate(text));
	}

	private boolean isLikelyBody(String value) {
		if (value.length() < MIN_BODY_CHARS || value.indexOf('\uFFFD') >= 0) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return !lower.startsWith("share this article")
			&& !lower.startsWith("best click")
			&& !lower.contains("슈퍼스타 브랜드 파워")
			&& !lower.contains("슈퍼스타 브랜드파워")
			&& !lower.contains("facebook twitter kakao")
			&& !lower.contains("internet explorer 8");
	}

	private String removeBoilerplate(String value) {
		return value
			.replaceAll("이미지\\s*확대보기", " ")
			.replaceAll("무단\\s*전재\\s*및\\s*재배포\\s*금지", " ")
			.replaceAll("\\s*이\\s*기사를\\s*공유합니다.*$", " ")
			.replaceAll("※?\\s*저작권자\\s*ⓒ[^\\n]*$", " ");
	}

	private String normalizeArticleText(String value) {
		return value.replace('\u00a0', ' ')
			.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replaceAll("[\\t\\x0B\\f ]+", " ")
			.replaceAll(" *\\n *", "\n")
			.replaceAll("\\n{3,}", "\n\n")
			.strip();
	}

	private String canonicalUrl(Document document, URI sourceUri) {
		Element canonical = document.selectFirst("link[rel=canonical]");
		if (canonical == null || canonical.attr("href").isBlank()) {
			return sourceUri.toString();
		}
		URI candidate = safeHttpUri(sourceUri.resolve(canonical.attr("href")).toString());
		return candidate == null ? sourceUri.toString() : candidate.toString();
	}

	private String imageUrl(Document document, URI sourceUri) {
		for (String selector : List.of(
			"meta[property=og:image]", "meta[property=og:image:url]", "meta[name=twitter:image]",
			"meta[itemprop=image]", "link[rel=image_src]"
		)) {
			Element element = document.selectFirst(selector);
			if (element == null) {
				continue;
			}
			String raw = element.hasAttr("content") ? element.attr("content") : element.attr("href");
			URI candidate = safeHttpUri(sourceUri.resolve(raw).toString());
			if (candidate != null && !isNonArticleImage(candidate)) {
				return candidate.toString();
			}
		}
		for (String selector : BODY_SELECTORS) {
			Element image = document.selectFirst(selector + " img");
			if (image != null) {
				String raw = Optional.ofNullable(image.attr("data-src"))
					.filter(value -> !value.isBlank())
					.orElse(image.attr("src"));
				URI candidate = safeHttpUri(sourceUri.resolve(raw).toString());
				if (candidate != null && !isNonArticleImage(candidate)) {
					return candidate.toString();
				}
			}
		}
		return null;
	}

	private boolean isNonArticleImage(URI uri) {
		String value = Optional.ofNullable(uri.getPath()).orElse("").toLowerCase(Locale.ROOT);
		return value.endsWith(".svg") || value.contains("logo") || value.contains("icon")
			|| value.contains("banner") || value.contains("profile") || value.contains("noimage")
			|| value.contains("spacer") || value.contains("1x1");
	}

	private URI safeHttpUri(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = new URI(value.strip()).normalize();
			String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
			int port = uri.getPort();
			if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
				|| uri.getUserInfo() != null || (port != -1 && port != 80 && port != 443)) {
				return null;
			}
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			if (host.equals("localhost") || host.endsWith(".localhost") || !allAddressesPublic(host)) {
				return null;
			}
			return uri;
		} catch (URISyntaxException exception) {
			return null;
		}
	}

	private boolean allAddressesPublic(String host) {
		try {
			InetAddress[] addresses = InetAddress.getAllByName(host);
			if (addresses.length == 0) {
				return false;
			}
			for (InetAddress address : addresses) {
				if (address.isAnyLocalAddress() || address.isLoopbackAddress()
					|| address.isLinkLocalAddress() || address.isSiteLocalAddress()
					|| address.isMulticastAddress()) {
					return false;
				}
			}
			return true;
		} catch (UnknownHostException exception) {
			return false;
		}
	}

	private record FetchedHtml(String html, URI sourceUri) {
	}
}
