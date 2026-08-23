package com.kmarket.navigator.backend.stock.infrastructure.kis;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KisMarketProperties.class)
class KisMarketConfiguration {

	@Bean("kisMarketRestClient")
	RestClient kisMarketRestClient(KisMarketProperties properties) {
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(properties.getConnectTimeout())
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(properties.getReadTimeout());
		return RestClient.builder()
			.baseUrl(properties.getBaseUrl().toString())
			.requestFactory(factory)
			.build();
	}
}
