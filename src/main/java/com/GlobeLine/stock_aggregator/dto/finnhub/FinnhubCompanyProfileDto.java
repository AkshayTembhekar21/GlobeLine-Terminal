package com.GlobeLine.stock_aggregator.dto.finnhub;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubCompanyProfileDto(
		@JsonProperty("ticker") String ticker,
		@JsonProperty("name") String name,
		@JsonProperty("country") String country,
		@JsonProperty("currency") String currency,
		@JsonProperty("exchange") String exchange,
		@JsonProperty("ipo") String ipoDate,
		@JsonProperty("marketCapitalization") BigDecimal marketCap,
		@JsonProperty("shareOutstanding") BigDecimal shareOutstanding,
		@JsonProperty("weburl") String website,
		@JsonProperty("logo") String logo,
		@JsonProperty("finnhubIndustry") String industry,
		@JsonProperty("phone") String phone) {
}

