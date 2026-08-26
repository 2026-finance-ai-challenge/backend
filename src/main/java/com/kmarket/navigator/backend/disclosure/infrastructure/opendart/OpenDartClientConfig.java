package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenDartProperties.class)
class OpenDartClientConfig {

	@Bean
	RestClient openDartRestClient(RestClient.Builder builder, OpenDartProperties properties) {
		return restClient(builder, "https://opendart.fss.or.kr", properties);
	}

	@Bean
	RestClient dartViewerRestClient(RestClient.Builder builder, OpenDartProperties properties) {
		return restClient(builder, "https://dart.fss.or.kr", properties);
	}

	private static RestClient restClient(
		RestClient.Builder builder,
		String baseUrl,
		OpenDartProperties properties
	) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return builder
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.build();
	}
}
