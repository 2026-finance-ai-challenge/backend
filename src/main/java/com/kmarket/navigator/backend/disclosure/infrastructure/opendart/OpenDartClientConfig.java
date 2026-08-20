package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenDartProperties.class)
class OpenDartClientConfig {

	@Bean
	RestClient openDartRestClient(RestClient.Builder builder) {
		return restClient(builder, "https://opendart.fss.or.kr");
	}

	@Bean
	RestClient dartViewerRestClient(RestClient.Builder builder) {
		return restClient(builder, "https://dart.fss.or.kr");
	}

	private static RestClient restClient(RestClient.Builder builder, String baseUrl) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(20));
		return builder
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.build();
	}
}
