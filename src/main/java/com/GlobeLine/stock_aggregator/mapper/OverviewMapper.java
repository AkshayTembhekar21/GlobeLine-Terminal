package com.GlobeLine.stock_aggregator.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.GlobeLine.stock_aggregator.dto.OhlcData;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCompanyProfileDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubMetricsDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubQuoteDto;

/**
 * Maps Finnhub API responses to our internal TickerOverviewDto.
 * Handles normalization and transformation of provider data.
 */
@Component
public class OverviewMapper {

	/**
	 * Combines all Finnhub DTOs into a single normalized overview DTO.
	 */
	public TickerOverviewDto mapToOverview(
			FinnhubCompanyProfileDto profile,
			FinnhubQuoteDto quote,
			FinnhubMetricsDto metrics,
			FinnhubCandleDto candles) {

		// Map OHLC candle data
		List<OhlcData> ohlcData = mapCandlesToOhlc(candles);

		// Build description from available data
		String description = buildDescription(profile);

		return new TickerOverviewDto(
				// Company Profile
				profile.name(),
				profile.industry(), // Using industry as sector
				profile.country(),
				profile.website(),

				// Live Quote
				quote.currentPrice(),
				quote.change(),
				quote.percentChange(),
				quote.high(),
				quote.low(),
				// Use quote volume if available, otherwise fallback to average volume from metrics
				getVolumeWithFallback(quote, metrics),
				profile.marketCap(),

				// Financial Snapshot
				// Revenue TTM (per share) - confirmed key exists
				metrics.metricValue("revenuePerShareTTM"),
				// Net Income TTM - calculate from netProfitMarginTTM * revenuePerShareTTM
				// (since netIncomePerShareTTM doesn't exist in Finnhub response)
				calculateNetIncomeTTM(metrics),
				metrics.metricValue("epsTTM"),
				metrics.metricValue("peAnnual"),
				metrics.metricValue("dividendYieldIndicatedAnnual"),

				// OHLC Data
				ohlcData,

				// Description
				description,

				// Metadata
				Instant.now());
	}

	/**
	 * Transforms Finnhub candle arrays into a list of OhlcData objects.
	 * Finnhub returns separate arrays for timestamps, open, high, low, close, volume.
	 * We zip them together into structured objects.
	 */
	private List<OhlcData> mapCandlesToOhlc(FinnhubCandleDto candles) {
		if (candles == null || !candles.isOk() || candles.timestamps() == null || candles.timestamps().isEmpty()) {
			return new ArrayList<>();
		}

		List<Long> timestamps = candles.timestamps();
		List<BigDecimal> opens = candles.open() != null ? candles.open() : new ArrayList<>();
		List<BigDecimal> highs = candles.high() != null ? candles.high() : new ArrayList<>();
		List<BigDecimal> lows = candles.low() != null ? candles.low() : new ArrayList<>();
		List<BigDecimal> closes = candles.close() != null ? candles.close() : new ArrayList<>();
		List<BigDecimal> volumes = candles.volume() != null ? candles.volume() : new ArrayList<>();

		int size = timestamps.size();

		return IntStream.range(0, size)
				.mapToObj(i -> new OhlcData(
						timestamps.get(i),
						i < opens.size() ? opens.get(i) : null,
						i < highs.size() ? highs.get(i) : null,
						i < lows.size() ? lows.get(i) : null,
						i < closes.size() ? closes.get(i) : null,
						i < volumes.size() ? volumes.get(i) : null))
				.toList();
	}

	/**
	 * Gets volume from quote, with fallback to average volume metrics if quote volume is null.
	 * Falls back to 10DayAverageTradingVolume or 3MonthAverageTradingVolume.
	 */
	private BigDecimal getVolumeWithFallback(FinnhubQuoteDto quote, FinnhubMetricsDto metrics) {
		// First try to get volume from quote (real-time)
		if (quote.volume() != null) {
			return quote.volume();
		}
		
		// If quote volume is null, try average volumes from metrics as fallback
		if (metrics != null) {
			// Try 10-day average first (more recent)
			BigDecimal avgVolume = metrics.metricValue("10DayAverageTradingVolume");
			if (avgVolume != null) {
				return avgVolume;
			}
			
			// Fallback to 3-month average
			avgVolume = metrics.metricValue("3MonthAverageTradingVolume");
			if (avgVolume != null) {
				return avgVolume;
			}
		}
		
		return null;
	}

	/**
	 * Calculates Net Income TTM from netProfitMarginTTM and revenuePerShareTTM.
	 * Formula: Net Income = Revenue * Net Profit Margin
	 * Since Finnhub doesn't provide netIncomePerShareTTM directly.
	 */
	private BigDecimal calculateNetIncomeTTM(FinnhubMetricsDto metrics) {
		if (metrics == null) {
			return null;
		}
		
		BigDecimal revenuePerShare = metrics.metricValue("revenuePerShareTTM");
		BigDecimal netProfitMargin = metrics.metricValue("netProfitMarginTTM");
		
		if (revenuePerShare != null && netProfitMargin != null) {
			// Net Income = Revenue * (Net Profit Margin / 100)
			// Net Profit Margin is typically a percentage (e.g., 25.5 means 25.5%)
			return revenuePerShare.multiply(netProfitMargin).divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
		}
		
		return null;
	}

	/**
	 * Builds a short company description from available profile data.
	 */
	private String buildDescription(FinnhubCompanyProfileDto profile) {
		StringBuilder desc = new StringBuilder();
		if (profile.name() != null) {
			desc.append(profile.name());
		}
		if (profile.industry() != null) {
			if (desc.length() > 0) {
				desc.append(" is a company in the ");
			}
			desc.append(profile.industry());
			desc.append(" industry");
		}
		if (profile.country() != null) {
			desc.append(" based in ").append(profile.country());
		}
		if (desc.length() == 0) {
			desc.append("Company information");
		}
		desc.append(".");
		return desc.toString();
	}
}

