package com.GlobeLine.stock_aggregator.service;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.GlobeLine.stock_aggregator.connectors.FinnhubClient.SymbolNotFoundException;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.exception.RateLimitExceededException;
import com.GlobeLine.stock_aggregator.exception.ServiceUnavailableException;
import com.GlobeLine.stock_aggregator.metrics.AggregationMetrics;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

/**
 * Decorator service that wraps CoreAggregatorService and adds metrics instrumentation.
 * 
 * Implements Decorator Pattern (wraps core service with metrics) and Observer Pattern
 * (receives business events from core service for metrics recording).
 */
@Service
public class AggregatorService implements AggregationEventObserver {

	private static final Logger logger = LoggerFactory.getLogger(AggregatorService.class);

	private final CoreAggregatorService coreService;
	private final AggregationMetrics metrics;
	private final RateLimiter rateLimiter;

	public AggregatorService(CoreAggregatorService coreService, AggregationMetrics metrics, RateLimiter rateLimiter) {
		this.coreService = coreService;
		this.metrics = metrics;
		this.rateLimiter = rateLimiter;
	}
	
	@PostConstruct
	public void wireObserver() {
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

	public Mono<TickerOverviewDto> getOverview(String symbol) {
		String upperSymbol = symbol.toUpperCase();
		Timer.Sample sample = metrics.startTimer();
		Mono<TickerOverviewDto> result = coreService.getOverview(upperSymbol);
		
		return result
				.transformDeferred(RateLimiterOperator.of(rateLimiter))
				.onErrorMap(io.github.resilience4j.ratelimiter.RequestNotPermitted.class, 
					ex -> new RateLimitExceededException("Rate limit exceeded: 100 requests per minute"))
				.doOnSuccess(overview -> {
					logger.debug("Successfully completed aggregation for symbol: {}", upperSymbol);
				})
				.doOnError(SymbolNotFoundException.class, ex -> {
					metrics.recordSymbolNotFound();
				})
				.doOnError(ServiceUnavailableException.class, ex -> {
					metrics.recordServiceUnavailable();
				})
				.doOnError(Exception.class, ex -> {
					if (!(ex instanceof ServiceUnavailableException) && 
					    !(ex instanceof SymbolNotFoundException)) {
						logger.error("Unexpected error type for symbol: {}", upperSymbol, ex);
					}
				})
				.doFinally(signalType -> {
					metrics.stopTimer(sample);
					logger.debug("Completed aggregation for symbol: {} with signal: {}", upperSymbol, signalType);
				});
	}
}
