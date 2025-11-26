package com.GlobeLine.stock_aggregator.service;

import java.time.Instant;

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
	 * First checks cache, then fetches from Finnhub if needed.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing the ticker overview
	 */
	public Mono<TickerOverviewDto> getOverview(String symbol) {
		String upperSymbol = symbol.toUpperCase();

		// Check cache first
		TickerOverviewDto cached = overviewCache.getIfPresent(upperSymbol);
		if (cached != null) {
			logger.debug("Cache hit for symbol: {}", upperSymbol);
			return Mono.just(cached);
		}

		logger.debug("Cache miss for symbol: {}, fetching from Finnhub", upperSymbol);

		// Fetch all data in parallel using Mono.zip
		return Mono.zip(
				finnhubClient.getCompanyProfile(upperSymbol),
				finnhubClient.getQuote(upperSymbol),
				finnhubClient.getMetrics(upperSymbol),
				finnhubClient.getCandles(upperSymbol))
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
				});
	}
}

