package com.GlobeLine.stock_aggregator.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;

import com.GlobeLine.stock_aggregator.connectors.FinnhubClient;
import com.GlobeLine.stock_aggregator.connectors.FinnhubClient.SymbolNotFoundException;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.exception.ServiceUnavailableException;
import com.GlobeLine.stock_aggregator.mapper.OverviewMapper;

import reactor.core.publisher.Mono;

/**
 * Core aggregation service containing pure business logic.
 * This service has no knowledge of metrics, logging concerns, or other cross-cutting aspects.
 * It focuses solely on the aggregation logic.
 */
@Component
public class CoreAggregatorService {

	private static final Logger logger = LoggerFactory.getLogger(CoreAggregatorService.class);

	private final FinnhubClient finnhubClient;
	private final OverviewMapper mapper;
	private final Cache<String, TickerOverviewDto> overviewCache;
	private AggregationEventObserver eventObserver;
	private final ConcurrentHashMap<String, Mono<TickerOverviewDto>> inFlightRequests = new ConcurrentHashMap<>();

	public CoreAggregatorService(
			FinnhubClient finnhubClient,
			OverviewMapper mapper,
			Cache<String, TickerOverviewDto> overviewCache) {
		this.finnhubClient = finnhubClient;
		this.mapper = mapper;
		this.overviewCache = overviewCache;
	}
	
	public void setObserver(AggregationEventObserver observer) {
		this.eventObserver = observer;
	}

	/**
	 * Gets the overview for a stock symbol.
	 * Checks cache first, then in-flight requests, then fetches from Finnhub if needed.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing the ticker overview
	 */
	public Mono<TickerOverviewDto> getOverview(String symbol) {
		String upperSymbol = symbol.toUpperCase();

		TickerOverviewDto cached = overviewCache.getIfPresent(upperSymbol);
		if (cached != null) {
			logger.debug("Cache hit for symbol: {}", upperSymbol);
			if (eventObserver != null) {
				eventObserver.onCacheHit();
			}
			return Mono.just(cached);
		}

		if (eventObserver != null) {
			eventObserver.onCacheMiss();
		}

		Mono<TickerOverviewDto> inFlight = inFlightRequests.get(upperSymbol);
		if (inFlight != null) {
			logger.debug("In-flight request found for symbol: {}, sharing the same operation", upperSymbol);
			if (eventObserver != null) {
				eventObserver.onInFlightSharing();
			}
			return inFlight;
		}

		logger.debug("Cache miss and no in-flight request for symbol: {}, creating new fetch operation", upperSymbol);
		Mono<com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto> candlesMono = finnhubClient
				.getCandles(upperSymbol)
				.onErrorResume(ex -> {
					logger.warn("Failed to fetch candles for symbol: {} - continuing without OHLC data. Error: {}", 
							upperSymbol, ex.getMessage());
					return Mono.just(new com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto(
							null, null, null, null, null, null, "error"));
				});

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

					TickerOverviewDto overview = mapper.mapToOverview(profile, quote, metrics, candles);
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
					TickerOverviewDto stale = overviewCache.getIfPresent(upperSymbol);
					if (stale != null) {
						logger.info("Returning stale cache for symbol: {}", upperSymbol);
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
				.cache()
				.doFinally(signalType -> {
					inFlightRequests.remove(upperSymbol);
					logger.debug("Removed in-flight request for symbol: {} (signal: {})", upperSymbol, signalType);
				});

		Mono<TickerOverviewDto> existing = inFlightRequests.putIfAbsent(upperSymbol, fetchOperation);
		if (existing != null) {
			logger.debug("Another thread created in-flight request for symbol: {}, using it", upperSymbol);
			return existing;
		}

		return fetchOperation;
	}
}

