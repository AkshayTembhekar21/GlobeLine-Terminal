package com.GlobeLine.stock_aggregator.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Complete overview response for a stock ticker.
 * This is what our API returns to clients.
 */
public record TickerOverviewDto(
		// Company Profile
		@JsonProperty("companyName") String companyName,
		@JsonProperty("sector") String sector,
		@JsonProperty("country") String country,
		@JsonProperty("website") String website,

		// Live Quote
		@JsonProperty("currentPrice") BigDecimal currentPrice,
		@JsonProperty("change") BigDecimal change,
		@JsonProperty("percentChange") BigDecimal percentChange,
		@JsonProperty("dayHigh") BigDecimal dayHigh,
		@JsonProperty("dayLow") BigDecimal dayLow,
		@JsonProperty("volume") BigDecimal volume,
		@JsonProperty("marketCap") BigDecimal marketCap,

		// Financial Snapshot
		@JsonProperty("revenueTTM") BigDecimal revenueTTM,
		@JsonProperty("netIncomeTTM") BigDecimal netIncomeTTM,
		@JsonProperty("eps") BigDecimal eps,
		@JsonProperty("peRatio") BigDecimal peRatio,
		@JsonProperty("dividendYield") BigDecimal dividendYield,

		// 1-Month OHLC Data for Charting
		@JsonProperty("ohlcData") List<OhlcData> ohlcData,

		// Company Description
		@JsonProperty("description") String description,

		// Metadata
		@JsonProperty("lastUpdated") Instant lastUpdated) {
}

