package com.GlobeLine.stock_aggregator.dto.finnhub;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubCandleDto(
		@JsonProperty("c") List<BigDecimal> close,
		@JsonProperty("o") List<BigDecimal> open,
		@JsonProperty("h") List<BigDecimal> high,
		@JsonProperty("l") List<BigDecimal> low,
		@JsonProperty("t") List<Long> timestamps,
		@JsonProperty("v") List<BigDecimal> volume,
		@JsonProperty("s") String status) {

	public boolean isOk() {
		return "ok".equalsIgnoreCase(status);
	}
}

