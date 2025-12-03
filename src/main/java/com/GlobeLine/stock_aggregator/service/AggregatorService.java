package com.GlobeLine.stock_aggregator.service;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.GlobeLine.stock_aggregator.connectors.FinnhubClient.SymbolNotFoundException;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.exception.ServiceUnavailableException;
import com.GlobeLine.stock_aggregator.metrics.AggregationMetrics;

import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

/**
 * Decorator service that wraps CoreAggregatorService and adds metrics instrumentation.
 * 
 * This class implements the Decorator Pattern:
 * - It wraps the CoreAggregatorService (the component being decorated)
 * - It implements the same interface (getOverview method)
 * - It adds metrics behavior without modifying the core business logic
 * - The core service has no knowledge of metrics or this decorator
 * 
 * How it works:
 * 1. Client calls AggregatorService.getOverview()
 * 2. Decorator starts timing and delegates to CoreAggregatorService
 * 3. Core service performs business logic (no metrics knowledge)
 * 4. Decorator wraps the returned Mono to add metrics on success/error
 * 5. Decorator stops timing when operation completes
 */
/**
 * Decorator service that wraps CoreAggregatorService and adds metrics instrumentation.
 * 
 * This class implements TWO patterns:
 * 1. Decorator Pattern - wraps core service and adds metrics behavior
 * 2. Observer Pattern - implements AggregationEventObserver to receive business events
 * 
 * The core service notifies this decorator about events (cache hits, etc.),
 * and this decorator handles recording metrics. Core service has no metrics knowledge!
 */
@Service
public class AggregatorService implements AggregationEventObserver {

	private static final Logger logger = LoggerFactory.getLogger(AggregatorService.class);

	private final CoreAggregatorService coreService;
	private final AggregationMetrics metrics;

	public AggregatorService(CoreAggregatorService coreService, AggregationMetrics metrics) {
		this.coreService = coreService;
		this.metrics = metrics;
	}
	
	/**
	 * Wire up the observer pattern after Spring creates this bean.
	 * This method runs after dependency injection is complete.
	 */
	@PostConstruct
	public void wireObserver() {
		// Set this decorator as the observer for core service
		// Core service will call our observer methods when events occur
		coreService.setObserver(this);
	}

	@Override
	public void onCacheHit() {
		metrics.recordCacheHit();
	}

	@Override
	public void onCacheMiss() {
		metrics.recordCacheMiss();
	}

	@Override
	public void onInFlightSharing() {
		metrics.recordInFlightSharing();
	}

	/**
	 * Gets the overview for a stock symbol with automatic metrics instrumentation.
	 * 
	 * This method:
	 * - Starts timing before delegating to core service
	 * - Wraps the Mono returned by core service with metrics recording
	 * - Automatically records errors by type (SymbolNotFound, ServiceUnavailable, etc.)
	 * - Stops timing when operation completes
	 * 
	 * All metrics are added automatically - no explicit metrics calls in business logic!
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Mono containing the ticker overview
	 */
	public Mono<TickerOverviewDto> getOverview(String symbol) {
		String upperSymbol = symbol.toUpperCase();
		
		// Start timing the entire operation
		Timer.Sample sample = metrics.startTimer();
		
		// Delegate to core service - it knows nothing about metrics
		Mono<TickerOverviewDto> result = coreService.getOverview(upperSymbol);
		
		// Wrap the Mono with metrics instrumentation
		// This is where we add cross-cutting concerns without touching business logic
		return result
				// Record metrics on success - we can add cache hit detection here if needed
				.doOnSuccess(overview -> {
					// Success - timing will be recorded in doFinally
					logger.debug("Successfully completed aggregation for symbol: {}", upperSymbol);
				})
				// Record error metrics automatically based on exception type
				.doOnError(SymbolNotFoundException.class, ex -> {
					metrics.recordSymbolNotFound();
				})
				.doOnError(ServiceUnavailableException.class, ex -> {
					metrics.recordServiceUnavailable();
				})
				.doOnError(Exception.class, ex -> {
					// Generic error - ServiceUnavailableException already handled above
					// But if there's any other exception, we could log it here
					if (!(ex instanceof ServiceUnavailableException) && 
					    !(ex instanceof SymbolNotFoundException)) {
						logger.error("Unexpected error type for symbol: {}", upperSymbol, ex);
					}
				})
				// Always stop the timer when operation completes (success or error)
				.doFinally(signalType -> {
					metrics.stopTimer(sample);
					logger.debug("Completed aggregation for symbol: {} with signal: {}", upperSymbol, signalType);
				});
	}
}
