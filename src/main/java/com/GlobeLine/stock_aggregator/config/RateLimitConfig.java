package com.GlobeLine.stock_aggregator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

@Configuration
public class RateLimitConfig {

	private static final int PERMITS_PER_SECOND = 100;
	private static final int PERIOD_IN_SECONDS = 60;

	@Bean
	public RateLimiter tickerOverviewRateLimiter() {
		RateLimiterConfig config = RateLimiterConfig.custom()
				.limitForPeriod(PERMITS_PER_SECOND)
				.limitRefreshPeriod(java.time.Duration.ofSeconds(PERIOD_IN_SECONDS))
				.timeoutDuration(java.time.Duration.ofSeconds(0))
				.build();

		return RateLimiter.of("tickerOverview", config);
	}
}

