package com.GlobeLine.stock_aggregator.config;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

@Configuration
@EnableConfigurationProperties(FinnhubApiProperties.class)
public class WebClientConfig {

	private static final Logger logger = LoggerFactory.getLogger(WebClientConfig.class);

	@Bean
	public WebClient finnhubWebClient(FinnhubApiProperties properties) {
		HttpClient httpClient = HttpClient.create()
				.responseTimeout(Duration.ofSeconds(15))
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

		return WebClient.builder()
				.baseUrl(properties.baseUrl())
				.defaultHeader("X-Finnhub-Token", properties.key())
				.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
				.filter(logRequestAndResponseWithLatency())
				.filter(retryFilter())
				.build();
	}

	private ExchangeFilterFunction logRequestAndResponseWithLatency() {
		return (clientRequest, next) -> {
			Instant startTime = Instant.now();
			String method = clientRequest.method().name();
			String url = clientRequest.url().toString();
			
			if (logger.isDebugEnabled()) {
				logger.debug("Outgoing request to Finnhub: {} {}", method, url);
			}
			
			return next.exchange(clientRequest)
					.doOnSuccess(response -> {
						Duration duration = Duration.between(startTime, Instant.now());
						int statusCode = response.statusCode().value();
						
						if (logger.isDebugEnabled()) {
							logger.debug("Received response from Finnhub: {} in {}ms", 
									statusCode, duration.toMillis());
						}
						
						if (response.statusCode().isError()) {
							logger.warn("Finnhub API returned error: {} for {} {} (took {}ms)", 
									statusCode, method, url, duration.toMillis());
						}
					})
					.doOnError(error -> {
						Duration duration = Duration.between(startTime, Instant.now());
						
						if (error instanceof WebClientResponseException webClientError) {
							HttpStatus status = HttpStatus.resolve(webClientError.getStatusCode().value());
							logger.error("Finnhub API request failed: {} {} for {} {} (took {}ms) - {}", 
									status != null ? status.value() : webClientError.getStatusCode().value(),
									status != null ? status.getReasonPhrase() : "Unknown",
									method, url, duration.toMillis(), webClientError.getMessage(), error);
						} else {
							logger.error("Finnhub API request failed for {} {} (took {}ms) - {}", 
									method, url, duration.toMillis(), error.getMessage(), error);
						}
					});
		};
	}

	private ExchangeFilterFunction retryFilter() {
		Retry retrySpec = Retry.backoff(3, Duration.ofMillis(200))
				.filter(WebClientResponseException.TooManyRequests.class::isInstance)
				.maxBackoff(Duration.ofSeconds(2));

		return (request, next) -> next.exchange(request)
				.retryWhen(retrySpec);
	}
}

