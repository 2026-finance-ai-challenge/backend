package com.kmarket.navigator.backend.tax.infrastructure.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaxDocumentProperties.class)
public class TaxDocumentStorageConfiguration {
}
