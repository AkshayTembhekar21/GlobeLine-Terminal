package com.GlobeLine.stock_aggregator.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Centralized metrics for stock aggregation operations.
 */
@Component
public class AggregationMetrics {

	private final Counter cacheHitCounter;
	private final Counter cacheMissCounter;
	private final Counter inFlightSharingCounter;
	private final Counter symbolNotFoundCounter;
	private final Counter serviceUnavailableCounter;
	private final Timer aggregationTimer;

	public AggregationMetrics(MeterRegistry meterRegistry) {
		this.cacheHitCounter = Counter.builder("stock.aggregator.cache.hits")
				.description("Number of cache hits when fetching ticker overview")
				.register(meterRegistry);
		
		this.cacheMissCounter = Counter.builder("stock.aggregator.cache.misses")
				.description("Number of cache misses when fetching ticker overview")
				.register(meterRegistry);
		
		this.inFlightSharingCounter = Counter.builder("stock.aggregator.inflight.sharing")
				.description("Number of times in-flight requests were shared (deduplication working)")
				.register(meterRegistry);
		
		this.symbolNotFoundCounter = Counter.builder("stock.aggregator.errors.symbol_not_found")
				.description("Number of symbol not found errors (404)")
				.tag("error_type", "symbol_not_found")
				.register(meterRegistry);
		
		this.serviceUnavailableCounter = Counter.builder("stock.aggregator.errors.service_unavailable")
				.description("Number of service unavailable errors (503)")
				.tag("error_type", "service_unavailable")
				.register(meterRegistry);
		
		this.aggregationTimer = Timer.builder("stock.aggregator.aggregation.duration")
				.description("Time taken to aggregate ticker overview (end-to-end)")
				.register(meterRegistry);
	}

	public void recordCacheHit() {
		cacheHitCounter.increment();
	}

	public void recordCacheMiss() {
		cacheMissCounter.increment();
	}

	public void recordInFlightSharing() {
		inFlightSharingCounter.increment();
	}

	public void recordSymbolNotFound() {
		symbolNotFoundCounter.increment();
	}

	public void recordServiceUnavailable() {
		serviceUnavailableCounter.increment();
	}

	public Timer.Sample startTimer() {
		return Timer.start();
	}

	public void stopTimer(Timer.Sample sample) {
		sample.stop(aggregationTimer);
	}
}

