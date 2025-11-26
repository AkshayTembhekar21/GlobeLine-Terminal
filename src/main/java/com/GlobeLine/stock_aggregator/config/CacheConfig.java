package com.GlobeLine.stock_aggregator.config;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;

/**
 * Configuration for in-memory caching using Caffeine.
 * TTLs:
 * - Overview: 30 seconds (live data changes frequently)
 * - Financials: 24 hours (changes less frequently)
 * - Candles: 5-15 minutes (depending on resolution)
 */
@Configuration
public class CacheConfig {

	/**
	 * Cache for ticker overview data with 30-second TTL.
	 */
	@Bean
	public Cache<String, TickerOverviewDto> overviewCache() {
		return Caffeine.newBuilder()
				.maximumSize(1000) // Max 1000 entries
				.expireAfterWrite(30, TimeUnit.SECONDS) // 30s TTL
				.recordStats() // Enable metrics
				.build();
	}
}

