package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiServiceProperties.class)
class AiServiceConfig {

	@Bean
	RestClient aiServiceRestClient(RestClient.Builder builder, AiServiceProperties properties) {
		return client(builder, properties, properties.readTimeout());
	}

	@Bean
	RestClient aiTranslationRestClient(RestClient.Builder builder, AiServiceProperties properties) {
		return client(builder, properties, java.time.Duration.ofSeconds(210));
	}

	private RestClient client(RestClient.Builder builder, AiServiceProperties properties, java.time.Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		return builder
			.baseUrl(properties.baseUrl().toString())
			.requestFactory(requestFactory)
			.build();
	}
}
