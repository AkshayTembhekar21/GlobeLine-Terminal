package com.GlobeLine.stock_aggregator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds Finnhub settings from application.properties (finnhub.api.*).
 */
@ConfigurationProperties(prefix = "finnhub.api")
public record FinnhubApiProperties(String baseUrl, String key) {
}

