package com.kmarket.navigator.backend.disclosure.infrastructure.stockmaster;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.application.port.ListedStockGateway;
import com.kmarket.navigator.backend.disclosure.application.port.ListedStockGatewayException;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;
import com.kmarket.navigator.backend.disclosure.domain.Market;

@Component
class KisStockMasterClient implements ListedStockGateway {

	private static final String KOSPI_URL =
		"https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip";
	private static final String KOSDAQ_URL =
		"https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip";
	private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
	private static final int MIN_KOSPI_COMMON_STOCKS = 700;
	private static final int MIN_KOSDAQ_COMMON_STOCKS = 1_500;
	private static final int MAX_ATTEMPTS = 3;

	private final RestClient restClient;
	private final KisStockMasterParser parser;

	KisStockMasterClient(RestClient.Builder builder, KisStockMasterParser parser) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(30));
		this.restClient = builder.requestFactory(requestFactory).build();
		this.parser = parser;
	}

	@Override
	public List<ListedCommonStock> fetchCommonStocks() {
		List<ListedCommonStock> kospi = fetch(KOSPI_URL, Market.KOSPI);
		List<ListedCommonStock> kosdaq = fetch(KOSDAQ_URL, Market.KOSDAQ);
		if (kospi.size() < MIN_KOSPI_COMMON_STOCKS || kosdaq.size() < MIN_KOSDAQ_COMMON_STOCKS) {
			throw new ListedStockGatewayException("Stock master count is below the safety threshold");
		}
		List<ListedCommonStock> stocks = new ArrayList<>(kospi.size() + kosdaq.size());
		stocks.addAll(kospi);
		stocks.addAll(kosdaq);
		if (new HashSet<>(stocks.stream().map(ListedCommonStock::stockCode).toList()).size()
			!= stocks.size()) {
			throw new ListedStockGatewayException("Stock master contains duplicate stock codes");
		}
		return List.copyOf(stocks);
	}

	private List<ListedCommonStock> fetch(String url, Market market) {
		RestClientException lastException = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				byte[] archive = restClient.get()
					.uri(url)
					.exchange((request, response) -> readResponse(response));
				return parser.parse(archive, market);
			}
			catch (RestClientException exception) {
				lastException = exception;
			}
		}
		throw new ListedStockGatewayException("Stock master download failed", lastException);
	}

	private static byte[] readResponse(ClientHttpResponse response) {
		try {
			HttpStatusCode status = response.getStatusCode();
			if (!status.is2xxSuccessful()) {
				throw new RestClientException("Stock master HTTP request failed");
			}
			byte[] content = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
			if (content.length > MAX_RESPONSE_BYTES) {
				throw new ListedStockGatewayException("Stock master response is too large");
			}
			return content;
		}
		catch (IOException exception) {
			throw new ListedStockGatewayException("Stock master response read failed", exception);
		}
	}
}
