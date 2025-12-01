package com.GlobeLine.stock_aggregator.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;

import com.GlobeLine.stock_aggregator.connectors.FinnhubClient;
import com.GlobeLine.stock_aggregator.connectors.FinnhubClient.SymbolNotFoundException;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.exception.ServiceUnavailableException;
import com.GlobeLine.stock_aggregator.mapper.OverviewMapper;

import reactor.core.publisher.Mono;

/**
 * Aggregates data from multiple Finnhub endpoints and returns a unified overview.
 * Implements caching and parallel API calls for performance.
 */
@Service
public class AggregatorService {

	private static final Logger logger = LoggerFactory.getLogger(AggregatorService.class);

	private final FinnhubClient finnhubClient;
	private final OverviewMapper mapper;
	private final Cache<String, TickerOverviewDto> overviewCache;
	
	// In-flight request map to prevent duplicate API calls for the same symbol
	// Key: symbol, Value: Mono<TickerOverviewDto> that's currently being executed
	// Multiple concurrent requests for the same symbol will share the same Mono
	private final ConcurrentHashMap<String, Mono<TickerOverviewDto>> inFlightRequests = new ConcurrentHashMap<>();

	public AggregatorService(
			FinnhubClient finnhubClient,
			OverviewMapper mapper,
			Cache<String, TickerOverviewDto> overviewCache) {
		this.finnhubClient = finnhubClient;
		this.mapper = mapper;
		this.overviewCache = overviewCache;
	}

	/**
	 * Gets the overview for a stock symbol.
	 * First checks cache, then checks in-flight requests, then fetches from Finnhub if needed.
	 * Prevents duplicate API calls for concurrent requests of the same symbol.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing the ticker overview
	 */
	public Mono<TickerOverviewDto> getOverview(String symbol) {
		String upperSymbol = symbol.toUpperCase();

		// Step 1: Check cache first (synchronous check for completed results)
		TickerOverviewDto cached = overviewCache.getIfPresent(upperSymbol);
		if (cached != null) {
			logger.debug("Cache hit for symbol: {}", upperSymbol);
			return Mono.just(cached);
		}

		// Step 2: Check if there's already an in-flight request for this symbol
		Mono<TickerOverviewDto> inFlight = inFlightRequests.get(upperSymbol);
		if (inFlight != null) {
			logger.debug("In-flight request found for symbol: {}, sharing the same operation", upperSymbol);
			return inFlight;
		}

		logger.debug("Cache miss and no in-flight request for symbol: {}, creating new fetch operation", upperSymbol);

		// Step 3: Create new fetch operation
		// Fetch critical data in parallel (profile, quote, metrics)
		// Candles are optional - if they fail, we'll use empty data
		Mono<com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto> candlesMono = finnhubClient
				.getCandles(upperSymbol)
				.onErrorResume(ex -> {
					logger.warn("Failed to fetch candles for symbol: {} - continuing without OHLC data. Error: {}", 
							upperSymbol, ex.getMessage());
					// Return empty candle DTO if candles fail
					return Mono.just(new com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto(
							null, null, null, null, null, null, "error"));
				});

		// Create the fetch operation
		Mono<TickerOverviewDto> fetchOperation = Mono.zip(
				finnhubClient.getCompanyProfile(upperSymbol),
				finnhubClient.getQuote(upperSymbol),
				finnhubClient.getMetrics(upperSymbol),
				candlesMono)
				.map(tuple -> {
					var profile = tuple.getT1();
					var quote = tuple.getT2();
					var metrics = tuple.getT3();
					var candles = tuple.getT4();

					// Normalize and combine data
					TickerOverviewDto overview = mapper.mapToOverview(profile, quote, metrics, candles);

					// Store in cache
					overviewCache.put(upperSymbol, overview);

					logger.info("Successfully aggregated overview for symbol: {}", upperSymbol);
					return overview;
				})
				.onErrorResume(SymbolNotFoundException.class, ex -> {
					logger.warn("Symbol not found: {}", upperSymbol);
					return Mono.error(ex);
				})
				.onErrorResume(Exception.class, ex -> {
					logger.error("Error fetching data for symbol: {}", upperSymbol, ex);
					// Try to return stale cache if available
					TickerOverviewDto stale = overviewCache.getIfPresent(upperSymbol);
					if (stale != null) {
						logger.info("Returning stale cache for symbol: {}", upperSymbol);
						// Update lastUpdated to indicate stale data
						TickerOverviewDto staleWithTimestamp = new TickerOverviewDto(
								stale.companyName(), stale.sector(), stale.country(), stale.website(),
								stale.currentPrice(), stale.change(), stale.percentChange(),
								stale.dayHigh(), stale.dayLow(), stale.volume(), stale.marketCap(),
								stale.revenueTTM(), stale.netIncomeTTM(), stale.eps(),
								stale.peRatio(), stale.dividendYield(), stale.ohlcData(),
								stale.description(), Instant.now());
						return Mono.just(staleWithTimestamp);
					}
					return Mono.error(new ServiceUnavailableException(
							"Temporarily unavailable - try again later", ex));
				})
				// Cache the result so multiple subscribers get the same result
				// This ensures that if multiple requests come in simultaneously, they all share the same operation
				.cache()
				// Clean up in-flight map when operation completes (success or error)
				.doFinally(signalType -> {
					inFlightRequests.remove(upperSymbol);
					logger.debug("Removed in-flight request for symbol: {} (signal: {})", upperSymbol, signalType);
				});

		// Store in in-flight map before returning
		// Use putIfAbsent to handle race condition where two threads might create operations simultaneously
		Mono<TickerOverviewDto> existing = inFlightRequests.putIfAbsent(upperSymbol, fetchOperation);
		if (existing != null) {
			// Another thread beat us to it, use their operation instead
			logger.debug("Another thread created in-flight request for symbol: {}, using it", upperSymbol);
			return existing;
		}

		return fetchOperation;
	}
}

