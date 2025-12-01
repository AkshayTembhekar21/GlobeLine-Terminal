package com.GlobeLine.stock_aggregator.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

	@Bean
	public WebClient finnhubWebClient(FinnhubApiProperties properties) {
		// Configure HTTP client with timeouts to prevent hanging requests
		HttpClient httpClient = HttpClient.create()
				.responseTimeout(Duration.ofSeconds(15)) // Total time to receive full response: 15 seconds
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000); // Time to establish connection: 5 seconds

		return WebClient.builder()
				.baseUrl(properties.baseUrl())
				.defaultHeader("X-Finnhub-Token", properties.key())
				.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
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

