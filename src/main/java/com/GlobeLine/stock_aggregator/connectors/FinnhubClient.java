package com.GlobeLine.stock_aggregator.connectors;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCompanyProfileDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubMetricsDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubQuoteDto;

import reactor.core.publisher.Mono;

@Component
public class FinnhubClient {

	private final WebClient finnhubWebClient;

	public FinnhubClient(@Qualifier("finnhubWebClient") WebClient finnhubWebClient) {
		this.finnhubWebClient = finnhubWebClient;
	}

	/**
	 * Fetches company profile information for a given symbol.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing company profile data
	 */
	public Mono<FinnhubCompanyProfileDto> getCompanyProfile(String symbol) {
		return finnhubWebClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/stock/profile2")
						.queryParam("symbol", symbol)
						.build())
				.retrieve()
				.bodyToMono(FinnhubCompanyProfileDto.class)
				.onErrorMap(WebClientResponseException.NotFound.class,
						ex -> new SymbolNotFoundException("Symbol not found: " + symbol, ex));
	}

	/**
	 * Fetches real-time quote data for a given symbol.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing quote data (price, change, volume, etc.)
	 */
	public Mono<FinnhubQuoteDto> getQuote(String symbol) {
		return finnhubWebClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/quote")
						.queryParam("symbol", symbol)
						.build())
				.retrieve()
				.bodyToMono(FinnhubQuoteDto.class)
				.onErrorMap(WebClientResponseException.NotFound.class,
						ex -> new SymbolNotFoundException("Symbol not found: " + symbol, ex));
	}

	/**
	 * Fetches financial metrics for a given symbol.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing metrics data (revenue, EPS, P/E, etc.)
	 */
	public Mono<FinnhubMetricsDto> getMetrics(String symbol) {
		return finnhubWebClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/stock/metric")
						.queryParam("symbol", symbol)
						.queryParam("metric", "all")
						.build())
				.retrieve()
				.bodyToMono(FinnhubMetricsDto.class)
				.onErrorMap(WebClientResponseException.NotFound.class,
						ex -> new SymbolNotFoundException("Symbol not found: " + symbol, ex));
	}

	/**
	 * Fetches 1-month OHLC (candle) data for a given symbol.
	 * Uses daily resolution and fetches data from 30 days ago to now.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing candle data (OHLC arrays)
	 */
	public Mono<FinnhubCandleDto> getCandles(String symbol) {
		Instant now = Instant.now();
		Instant thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);

		long fromTimestamp = thirtyDaysAgo.getEpochSecond();
		long toTimestamp = now.getEpochSecond();

		return finnhubWebClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/stock/candle")
						.queryParam("symbol", symbol)
						.queryParam("resolution", "D") // Daily resolution
						.queryParam("from", fromTimestamp)
						.queryParam("to", toTimestamp)
						.build())
				.retrieve()
				.bodyToMono(FinnhubCandleDto.class)
				.onErrorMap(WebClientResponseException.NotFound.class,
						ex -> new SymbolNotFoundException("Symbol not found: " + symbol, ex));
	}

	/**
	 * Custom exception for when a symbol is not found (404 from Finnhub).
	 */
	public static class SymbolNotFoundException extends RuntimeException {
		public SymbolNotFoundException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}

