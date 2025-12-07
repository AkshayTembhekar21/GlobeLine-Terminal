package com.GlobeLine.stock_aggregator.service;

/**
 * Observer interface for aggregation events.
 * Allows core service to notify about business events without knowing about metrics implementation.
 */
public interface AggregationEventObserver {
	
	void onCacheHit();
	void onCacheMiss();
	void onInFlightSharing();
}

