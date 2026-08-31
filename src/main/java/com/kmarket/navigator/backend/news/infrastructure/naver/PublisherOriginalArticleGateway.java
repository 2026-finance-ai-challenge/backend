package com.kmarket.navigator.backend.news.infrastructure.naver;

import java.io.IOException;
import java.io.InputStream;
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
	private static final String SOURCE_POLICY = "publisher_public_article_v1";
	private static final int MAX_REDIRECTS = 5;
	private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
	private static final int MAX_CONTENT_CHARS = 1_000_000;
	private static final int MIN_BODY_CHARS = 100;
	private static final Pattern SCRIPT_REDIRECT = Pattern.compile(
		"(?:top\\.|window\\.|document\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]",
		Pattern.CASE_INSENSITIVE
	);
	private static final List<String> BODY_SELECTORS = List.of(
		"#divNewsContent", "#textBody", "[itemprop=articleBody]", "#article-view-content-div",
		".article-body-only", "#articleBody", "#article_body", "#CmAdContent", ".acem_text",
		"#article", "#article_main", "#cont_newstext", ".detail-body", ".news_body",
		".news-content", ".article_body", ".article-body", "#news_body", ".article_content",
		".article-content", ".article_txt", ".view_content", ".newsct_article", "#dic_area",
		".go_trans._article_content", "main article", "article", "main"
	);
	private static final String NON_CONTENT_SELECTOR = String.join(",",
		"script", "style", "noscript", "iframe", "figure", "figcaption", "form", "button",
		"nav", "aside", "table", "[hidden]", ".ad", ".ads", ".advertisement",
		"[class*=advert]", "[id*=advert]", "[class*=banner]", "[id*=banner]",
		"[class*=share]", "[id*=share]", "[class*=sns]", "[id*=sns]",
		"[class*=recommend]", "[id*=recommend]", "[class*=related]", "[id*=related]",
		"[class*=copyright]", "[id*=copyright]", "[class*=reporter]", "[id*=reporter]"
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
			FetchedHtml fetched = fetchHtml(uri, 0);
			Optional<OriginalNewsArticle> parsed = parse(fetched);
			if (parsed.isPresent()) {
				return parsed;
			}
			for (URI alternate : alternateUris(fetched)) {
				Optional<OriginalNewsArticle> alternateArticle = parse(fetchHtml(alternate, 0));
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

	private FetchedHtml fetchHtml(URI uri, int redirectCount) throws IOException, InterruptedException {
		if (safeHttpUri(uri.toString()) == null) {
			throw new IllegalArgumentException("unsafe news URL");
		}
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(readTimeout)
			.header("User-Agent", "K-Market-Navigator/1.0")
			.header("Accept", "text/html,application/xhtml+xml;q=0.9")
			.header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8")
			.GET()
			.build();
		HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			if (response.statusCode() >= 300 && response.statusCode() < 400) {
				if (redirectCount >= MAX_REDIRECTS) {
					throw new IllegalStateException("too many redirects");
				}
				String location = response.headers().firstValue("location").orElse("");
				URI redirected = safeHttpUri(uri.resolve(location).toString());
				if (redirected == null) {
					throw new IllegalArgumentException("unsafe redirect URL");
				}
				return fetchHtml(redirected, redirectCount + 1);
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("publisher rejected request");
			}
			String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
			if (!contentType.isBlank() && !contentType.contains("text/html")
				&& !contentType.contains("application/xhtml+xml")) {
				throw new IllegalStateException("publisher response is not HTML");
			}
			byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
			if (bytes.length > MAX_RESPONSE_BYTES) {
				throw new IllegalStateException("publisher response exceeds size limit");
			}
			return new FetchedHtml(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), uri);
		}
	}

	private Optional<OriginalNewsArticle> parse(FetchedHtml fetched) {
		if (fetched.html().isBlank()) {
			return Optional.empty();
		}
		Document document = Jsoup.parse(fetched.html(), fetched.sourceUri().toString());
		String canonicalUrl = canonicalUrl(document, fetched.sourceUri());
		String thumbnailUrl = imageUrl(document, fetched.sourceUri());
		String selected = "";
		for (String selector : BODY_SELECTORS) {
			for (Element element : document.select(selector)) {
				String candidate = cleanArticleText(element);
				if (isLikelyBody(candidate) && candidate.length() > selected.length()) {
					selected = candidate;
				}
			}
		}
		if (selected.isBlank()) {
			return Optional.empty();
		}
		String body = selected.length() > MAX_CONTENT_CHARS ? selected.substring(0, MAX_CONTENT_CHARS) : selected;
		return Optional.of(new OriginalNewsArticle(body, canonicalUrl, thumbnailUrl, SOURCE_POLICY));
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
		List<String> paragraphs = copy.select("p,li,blockquote,h1,h2,h3,h4").stream()
			.map(Element::text)
			.map(this::removeBoilerplate)
			.filter(value -> !value.isBlank())
			.distinct()
			.toList();
		String text = paragraphs.size() >= 2 ? String.join("\n\n", paragraphs) : copy.wholeText();
		return normalizeArticleText(removeBoilerplate(text));
	}

	private boolean isLikelyBody(String value) {
		if (value.length() < MIN_BODY_CHARS) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return !lower.startsWith("share this article")
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
