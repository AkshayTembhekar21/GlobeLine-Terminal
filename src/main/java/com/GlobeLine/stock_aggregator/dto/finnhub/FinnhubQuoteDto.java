package com.GlobeLine.stock_aggregator.dto.finnhub;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubQuoteDto(
		@JsonProperty("c") BigDecimal currentPrice,
		@JsonProperty("d") BigDecimal change,
		@JsonProperty("dp") BigDecimal percentChange,
		@JsonProperty("h") BigDecimal high,
		@JsonProperty("l") BigDecimal low,
		@JsonProperty("o") BigDecimal open,
		@JsonProperty("pc") BigDecimal previousClose,
		@JsonProperty("t") Long timestamp,
		@JsonProperty("v") BigDecimal volume) {
}

