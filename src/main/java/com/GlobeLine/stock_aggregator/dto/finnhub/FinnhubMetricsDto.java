package com.GlobeLine.stock_aggregator.dto.finnhub;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubMetricsDto(
		@JsonProperty("metricType") String metricType,
		@JsonProperty("metric") Map<String, BigDecimal> metrics) {

	public BigDecimal metricValue(String key) {
		return metrics != null ? metrics.get(key) : null;
	}
}

