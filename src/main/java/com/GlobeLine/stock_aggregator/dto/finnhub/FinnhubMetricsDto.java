package com.GlobeLine.stock_aggregator.dto.finnhub;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubMetricsDto(
		@JsonProperty("metricType") String metricType,
		@JsonProperty("metric") Map<String, Object> metrics) {

	/**
	 * Safely extracts a BigDecimal value from the metrics map.
	 * Handles cases where the value might be a String, Number, or null.
	 */
	public BigDecimal metricValue(String key) {
		if (metrics == null || key == null) {
			return null;
		}
		
		Object value = metrics.get(key);
		if (value == null) {
			return null;
		}
		
		// If it's already a BigDecimal, return it
		if (value instanceof BigDecimal) {
			return (BigDecimal) value;
		}
		
		// If it's a Number, convert to BigDecimal
		if (value instanceof Number) {
			return BigDecimal.valueOf(((Number) value).doubleValue());
		}
		
		// If it's a String, try to parse it (but skip date strings)
		if (value instanceof String) {
			String strValue = (String) value;
			// Skip date strings (format: YYYY-MM-DD)
			if (strValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
				return null;
			}
			try {
				return new BigDecimal(strValue);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		
		return null;
	}

	/**
	 * Returns all available metric keys (for debugging).
	 */
	public java.util.Set<String> getAllKeys() {
		return metrics != null ? metrics.keySet() : java.util.Collections.emptySet();
	}
}

