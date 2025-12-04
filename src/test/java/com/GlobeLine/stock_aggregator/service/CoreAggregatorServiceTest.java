package com.GlobeLine.stock_aggregator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.github.benmanes.caffeine.cache.Cache;

import com.GlobeLine.stock_aggregator.connectors.FinnhubClient;
import com.GlobeLine.stock_aggregator.connectors.FinnhubClient.SymbolNotFoundException;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCompanyProfileDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubMetricsDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubQuoteDto;
import com.GlobeLine.stock_aggregator.mapper.OverviewMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for CoreAggregatorService.
 * Tests the business logic with mocked dependencies.
 */
class CoreAggregatorServiceTest {

	@Mock
	private FinnhubClient finnhubClient;

	@Mock
	private OverviewMapper mapper;

	@Mock
	private Cache<String, TickerOverviewDto> overviewCache;

	@Mock
	private AggregationEventObserver eventObserver;

	private CoreAggregatorService service;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		service = new CoreAggregatorService(finnhubClient, mapper, overviewCache);
		service.setObserver(eventObserver);
	}

	@Test
	void getOverview_CacheHit_ReturnsCachedData() {
		// Arrange
		String symbol = "AAPL";
		TickerOverviewDto cachedOverview = createMockOverview();
		
		when(overviewCache.getIfPresent(symbol)).thenReturn(cachedOverview);

		// Act
		Mono<TickerOverviewDto> result = service.getOverview(symbol);

		// Assert
		StepVerifier.create(result)
				.assertNext(overview -> {
					assertThat(overview).isEqualTo(cachedOverview);
				})
				.verifyComplete();

		// Verify cache was checked and observer was notified
		verify(overviewCache).getIfPresent(symbol);
		verify(eventObserver).onCacheHit();
		
		// Verify no API calls were made
		verify(finnhubClient, never()).getCompanyProfile(anyString());
		verify(finnhubClient, never()).getQuote(anyString());
	}

	@Test
	void getOverview_CacheMiss_FetchesFromApi() {
		// Arrange
		String symbol = "AAPL";
		when(overviewCache.getIfPresent(symbol)).thenReturn(null);
		
		// Mock API responses
		FinnhubCompanyProfileDto profile = createMockProfile();
		FinnhubQuoteDto quote = createMockQuote();
		FinnhubMetricsDto metrics = createMockMetrics();
		FinnhubCandleDto candles = createMockCandles();
		
		when(finnhubClient.getCompanyProfile(symbol)).thenReturn(Mono.just(profile));
		when(finnhubClient.getQuote(symbol)).thenReturn(Mono.just(quote));
		when(finnhubClient.getMetrics(symbol)).thenReturn(Mono.just(metrics));
		when(finnhubClient.getCandles(symbol)).thenReturn(Mono.just(candles));
		
		TickerOverviewDto expectedOverview = createMockOverview();
		when(mapper.mapToOverview(profile, quote, metrics, candles))
				.thenReturn(expectedOverview);

		// Act
		Mono<TickerOverviewDto> result = service.getOverview(symbol);

		// Assert
		StepVerifier.create(result)
				.assertNext(overview -> {
					assertThat(overview).isEqualTo(expectedOverview);
				})
				.verifyComplete();

		// Verify cache miss was notified
		verify(eventObserver).onCacheMiss();
		
		// Verify API calls were made
		verify(finnhubClient).getCompanyProfile(symbol);
		verify(finnhubClient).getQuote(symbol);
		verify(finnhubClient).getMetrics(symbol);
		verify(finnhubClient).getCandles(symbol);
		
		// Verify result was cached
		verify(overviewCache).put(symbol, expectedOverview);
	}

	@Test
	void getOverview_SymbolNotFound_PropagatesException() {
		// Arrange
		String symbol = "INVALID";
		when(overviewCache.getIfPresent(symbol)).thenReturn(null);
		when(finnhubClient.getCompanyProfile(symbol))
				.thenReturn(Mono.error(new SymbolNotFoundException("Symbol not found: INVALID", null)));

		// Act & Assert
		StepVerifier.create(service.getOverview(symbol))
				.expectError(SymbolNotFoundException.class)
				.verify();

		verify(eventObserver).onCacheMiss();
	}

	@Test
	void getOverview_CandlesError_ContinuesWithoutCandles() {
		// Arrange
		String symbol = "AAPL";
		when(overviewCache.getIfPresent(symbol)).thenReturn(null);
		
		FinnhubCompanyProfileDto profile = createMockProfile();
		FinnhubQuoteDto quote = createMockQuote();
		FinnhubMetricsDto metrics = createMockMetrics();
		
		when(finnhubClient.getCompanyProfile(symbol)).thenReturn(Mono.just(profile));
		when(finnhubClient.getQuote(symbol)).thenReturn(Mono.just(quote));
		when(finnhubClient.getMetrics(symbol)).thenReturn(Mono.just(metrics));
		when(finnhubClient.getCandles(symbol))
				.thenReturn(Mono.error(new RuntimeException("Candles unavailable")));
		
		TickerOverviewDto expectedOverview = createMockOverview();
		when(mapper.mapToOverview(any(), any(), any(), any()))
				.thenReturn(expectedOverview);

		// Act
		Mono<TickerOverviewDto> result = service.getOverview(symbol);

		// Assert
		StepVerifier.create(result)
				.assertNext(overview -> {
					assertThat(overview).isNotNull();
				})
				.verifyComplete();

		// Verify candles error was handled (service should continue)
		verify(finnhubClient).getCandles(symbol);
	}

	// Helper methods to create mock data
	private TickerOverviewDto createMockOverview() {
		return new TickerOverviewDto(
				"Apple Inc.",
				"Technology",
				"US",
				"https://www.apple.com",
				new BigDecimal("150.25"),
				new BigDecimal("2.50"),
				new BigDecimal("1.69"),
				new BigDecimal("151.00"),
				new BigDecimal("149.50"),
				new BigDecimal("50000000"),
				new BigDecimal("3000000000000"),
				new BigDecimal("23.5"),
				new BigDecimal("5.88"),
				new BigDecimal("2.50"),
				new BigDecimal("28.5"),
				new BigDecimal("0.005"),
				List.of(),
				"Apple Inc. is a company in the Technology industry based in US.",
				Instant.now());
	}

	private FinnhubCompanyProfileDto createMockProfile() {
		return new FinnhubCompanyProfileDto(
				"AAPL",
				"Apple Inc.",
				"US",
				"USD",
				"NASDAQ",
				"1980-12-12",
				new BigDecimal("3000000000000"),
				new BigDecimal("15000000000"),
				"https://www.apple.com",
				"https://logo.url",
				"Technology",
				"1-800-123-4567");
	}

	private FinnhubQuoteDto createMockQuote() {
		return new FinnhubQuoteDto(
				new BigDecimal("150.25"),
				new BigDecimal("2.50"),
				new BigDecimal("1.69"),
				new BigDecimal("151.00"),
				new BigDecimal("149.50"),
				new BigDecimal("150.00"),
				new BigDecimal("147.75"),
				1640995200L,
				new BigDecimal("50000000"));
	}

	private FinnhubMetricsDto createMockMetrics() {
		return new FinnhubMetricsDto(
				"all",
				Map.of(
						"revenuePerShareTTM", new BigDecimal("23.5"),
						"netProfitMarginTTM", new BigDecimal("25.0"),
						"epsTTM", new BigDecimal("2.50"),
						"peAnnual", new BigDecimal("28.5"),
						"dividendYieldIndicatedAnnual", new BigDecimal("0.005")));
	}

	private FinnhubCandleDto createMockCandles() {
		return new FinnhubCandleDto(
				List.of(new BigDecimal("150.0")),
				List.of(new BigDecimal("149.0")),
				List.of(new BigDecimal("151.0")),
				List.of(new BigDecimal("148.0")),
				List.of(1640995200L),
				List.of(new BigDecimal("1000000")),
				"ok");
	}
}

