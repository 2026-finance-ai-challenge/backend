package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import java.net.http.HttpClient;
import java.time.Duration;

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
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(30));
		return builder
			.baseUrl(properties.baseUrl().toString())
			.requestFactory(requestFactory)
			.build();
	}
}
