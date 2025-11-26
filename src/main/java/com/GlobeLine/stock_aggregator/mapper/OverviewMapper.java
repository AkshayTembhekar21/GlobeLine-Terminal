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
				quote.volume(),
				profile.marketCap(),

				// Financial Snapshot
				metrics.metricValue("revenuePerShareTTM"),
				metrics.metricValue("netIncomePerShareTTM"),
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

