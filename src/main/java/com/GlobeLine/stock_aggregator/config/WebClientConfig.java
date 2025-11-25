package com.GlobeLine.stock_aggregator.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Configuration
@EnableConfigurationProperties(FinnhubApiProperties.class)
public class WebClientConfig {

	@Bean
	public WebClient finnhubWebClient(FinnhubApiProperties properties) {
		return WebClient.builder()
				.baseUrl(properties.baseUrl())
				.defaultHeader("X-Finnhub-Token", properties.key())
				.filter(logRequest())
				.filter(retryFilter())
				.build();
	}

	private ExchangeFilterFunction logRequest() {
		return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
			System.out.println("Calling Finnhub: " + clientRequest.url());
			return Mono.just(clientRequest);
		});
	}

	private ExchangeFilterFunction retryFilter() {
		Retry retrySpec = Retry.backoff(3, Duration.ofMillis(200))
				.filter(WebClientResponseException.TooManyRequests.class::isInstance)
				.maxBackoff(Duration.ofSeconds(2));

		return (request, next) -> next.exchange(request)
				.retryWhen(retrySpec);
	}
}

