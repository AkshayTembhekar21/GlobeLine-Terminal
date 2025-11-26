package com.GlobeLine.stock_aggregator.dto;

import java.math.BigDecimal;

/**
 * Represents a single OHLC (Open, High, Low, Close) data point for charting.
 */
public record OhlcData(
		Long timestamp,
		BigDecimal open,
		BigDecimal high,
		BigDecimal low,
		BigDecimal close,
		BigDecimal volume) {
}

