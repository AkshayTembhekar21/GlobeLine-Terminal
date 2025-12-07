package com.GlobeLine.stock_aggregator.health;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.GlobeLine.stock_aggregator.config.FinnhubApiProperties;
import com.GlobeLine.stock_aggregator.connectors.FinnhubClient;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Health indicator that checks the connectivity and availability of the Finnhub API.
 * 
 * This health check performs a lightweight API call (quote endpoint) to verify:
 * 1. Network connectivity to Finnhub
 * 2. API authentication is working
 * 3. API is responding within acceptable time
 * 
 * The health check uses a well-known symbol (AAPL) and has a short timeout
 * to avoid blocking the health endpoint for too long.
 */
@Component
public class FinnhubApiHealthIndicator implements HealthIndicator {

	private static final Logger logger = LoggerFactory.getLogger(FinnhubApiHealthIndicator.class);
	
	private static final String HEALTH_CHECK_SYMBOL = "AAPL";
	private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);
	
	private final FinnhubClient finnhubClient;
	private final FinnhubApiProperties apiProperties;

	public FinnhubApiHealthIndicator(FinnhubClient finnhubClient, FinnhubApiProperties apiProperties) {
		this.finnhubClient = finnhubClient;
		this.apiProperties = apiProperties;
	}

	@Override
	public Health health() {
		try {
			finnhubClient.getQuote(HEALTH_CHECK_SYMBOL)
					.timeout(HEALTH_CHECK_TIMEOUT)
					.retryWhen(Retry.backoff(1, Duration.ofMillis(100))
							.filter(WebClientResponseException.TooManyRequests.class::isInstance))
					.doOnSuccess(quote -> logger.debug("Finnhub API health check passed for symbol: {}", HEALTH_CHECK_SYMBOL))
					.doOnError(error -> logger.warn("Finnhub API health check failed: {}", error.getMessage()))
					.block();
			
			return Health.up()
					.withDetail("api", "Finnhub API")
					.withDetail("baseUrl", apiProperties.baseUrl())
					.withDetail("status", "reachable")
					.withDetail("healthCheckSymbol", HEALTH_CHECK_SYMBOL)
					.build();
					
		} catch (WebClientResponseException ex) {
			int statusCode = ex.getStatusCode().value();
			String errorMessage = "HTTP " + statusCode;
			
			if (statusCode == 401 || statusCode == 403) {
				logger.error("Finnhub API health check failed: Unauthorized ({} - {}) - check API key configuration", 
						statusCode, ex.getStatusText(), ex);
				errorMessage = "Unauthorized - invalid API key";
			} else if (statusCode >= 500) {
				logger.warn("Finnhub API health check failed: Server error ({})", statusCode, ex);
				errorMessage = "Server error - service unavailable";
			} else {
				logger.warn("Finnhub API health check failed: HTTP error ({})", statusCode, ex);
				errorMessage = "HTTP error " + statusCode;
			}
			
			return Health.down()
					.withDetail("api", "Finnhub API")
					.withDetail("baseUrl", apiProperties.baseUrl())
					.withDetail("error", errorMessage)
					.withDetail("statusCode", statusCode)
					.withDetail("statusText", ex.getStatusText())
					.build();
					
		} catch (Exception ex) {
			String errorMessage = ex.getMessage();
			String errorType = ex.getClass().getSimpleName();
			boolean isTimeout = errorType.contains("Timeout") || 
							   (errorMessage != null && errorMessage.toLowerCase().contains("timeout"));
			
			if (isTimeout) {
				logger.warn("Finnhub API health check failed: Request timeout after {} seconds", HEALTH_CHECK_TIMEOUT.getSeconds(), ex);
				return Health.down()
						.withDetail("api", "Finnhub API")
						.withDetail("baseUrl", apiProperties.baseUrl())
						.withDetail("error", "Request timeout")
						.withDetail("timeoutSeconds", HEALTH_CHECK_TIMEOUT.getSeconds())
						.build();
			}
			
			logger.error("Finnhub API health check failed: Unexpected error", ex);
			return Health.down()
					.withDetail("api", "Finnhub API")
					.withDetail("baseUrl", apiProperties.baseUrl())
					.withDetail("error", errorMessage != null ? errorMessage : "Unknown error")
					.withDetail("errorType", errorType)
					.build();
		}
	}
}

