package com.GlobeLine.stock_aggregator.service;

/**
 * Observer interface for aggregation events.
 * 
 * This allows the core service to notify about business events (cache hits, 
 * in-flight sharing) without knowing about metrics implementation details.
 * 
 * The decorator implements this interface and handles metrics recording.
 * This way, core service stays clean - it just notifies about events,
 * and doesn't know HOW those events are recorded.
 */
public interface AggregationEventObserver {
	
	/**
	 * Called when a cache hit occurs.
	 */
	void onCacheHit();
	
	/**
	 * Called when a cache miss occurs.
	 */
	void onCacheMiss();
	
	/**
	 * Called when an in-flight request is shared (deduplication working).
	 */
	void onInFlightSharing();
}

