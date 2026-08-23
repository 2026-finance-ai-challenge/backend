package com.kmarket.navigator.backend.news.infrastructure.naver;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NaverNewsProperties.class)
class NaverNewsConfiguration {

	@Bean("naverNewsRestClient")
	RestClient naverNewsRestClient(NaverNewsProperties properties) {
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(properties.getConnectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(properties.getReadTimeout());
		return RestClient.builder()
			.baseUrl(properties.getBaseUrl().toString())
			.requestFactory(factory)
			.build();
	}
}
