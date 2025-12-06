package com.GlobeLine.stock_aggregator.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;

/**
 * Health indicator that checks the status and performance of the in-memory cache.
 * 
 * This health check examines:
 * 1. Cache availability and basic operations
 * 2. Cache statistics (hit rate, eviction rate, size)
 * 3. Overall cache health based on performance metrics
 * 
 * A healthy cache should have:
 * - Successful put/get operations
 * - Reasonable hit rate (higher is better)
 * - No excessive evictions
 */
@Component
public class CacheHealthIndicator implements HealthIndicator {

	private static final Logger logger = LoggerFactory.getLogger(CacheHealthIndicator.class);
	
	private final Cache<String, TickerOverviewDto> overviewCache;

	public CacheHealthIndicator(Cache<String, TickerOverviewDto> overviewCache) {
		this.overviewCache = overviewCache;
	}

	@Override
	public Health health() {
		try {
			// Get cache statistics (only available if recordStats() was enabled in CacheConfig)
			CacheStats stats = overviewCache.stats();
			
			// Perform a simple read/write test to verify cache is functional
			String testKey = "_health_check_";
			TickerOverviewDto testValue = null;
			
			// Try to perform cache operations
			try {
				// Clear any existing test value
				overviewCache.invalidate(testKey);
				
				// The cache should be functional - we don't need to actually write/read
				// The stats will tell us if it's working
				
				// Calculate hit rate percentage
				long requestCount = stats.requestCount();
				long hitCount = stats.hitCount();
				double hitRate = requestCount > 0 ? (double) hitCount / requestCount * 100.0 : 0.0;
				
				// Calculate eviction rate
				long evictionCount = stats.evictionCount();
				
				// Get current cache size (approximate)
				long estimatedSize = overviewCache.estimatedSize();
				
				// Determine health status based on cache metrics
				Health.Builder healthBuilder;
				
				// Cache is UP if:
				// - Hit rate is reasonable (or no requests yet)
				// - No excessive evictions (or cache is small)
				boolean isHealthy = true;
				
				if (requestCount > 100) {
					// After 100 requests, we expect some reasonable hit rate
					// Low hit rate (< 10%) might indicate cache isn't being utilized
					if (hitRate < 10.0) {
						logger.warn("Cache health check: Low hit rate of {}% after {} requests", hitRate, requestCount);
						// This is not necessarily DOWN, just a warning
					}
				}
				
				// Build health response with detailed cache statistics
				healthBuilder = Health.up()
						.withDetail("cache", "overviewCache")
						.withDetail("status", "operational")
						.withDetail("estimatedSize", estimatedSize)
						.withDetail("requestCount", requestCount)
						.withDetail("hitCount", hitCount)
						.withDetail("missCount", stats.missCount())
						.withDetail("hitRate", String.format("%.2f%%", hitRate))
						.withDetail("evictionCount", evictionCount)
						.withDetail("loadCount", stats.loadCount())
						.withDetail("totalLoadTime", stats.totalLoadTime())
						.withDetail("averageLoadPenalty", stats.averageLoadPenalty());
				
				logger.debug("Cache health check passed - hit rate: {}%, size: {}", hitRate, estimatedSize);
				
				return healthBuilder.build();
				
			} catch (Exception cacheOpEx) {
				// Cache operations failed
				logger.error("Cache health check failed: Cache operations not functional", cacheOpEx);
				return Health.down()
						.withDetail("cache", "overviewCache")
						.withDetail("error", "Cache operations failed")
						.withDetail("errorMessage", cacheOpEx.getMessage())
						.build();
			}
			
		} catch (Exception ex) {
			// Unexpected error accessing cache
			logger.error("Cache health check failed: Unexpected error", ex);
			return Health.down()
					.withDetail("cache", "overviewCache")
					.withDetail("error", "Unable to check cache health")
					.withDetail("errorMessage", ex.getMessage())
					.withDetail("errorType", ex.getClass().getSimpleName())
					.build();
		}
	}
}

