package com.GlobeLine.stock_aggregator.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.GlobeLine.stock_aggregator.connectors.FinnhubClient.SymbolNotFoundException;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.exception.ServiceUnavailableException;
import com.GlobeLine.stock_aggregator.service.AggregatorService;

import reactor.core.publisher.Mono;

/**
 * REST controller for ticker overview endpoint.
 */
@RestController
@RequestMapping("/api/ticker")
public class TickerController {

	private final AggregatorService aggregatorService;

	public TickerController(AggregatorService aggregatorService) {
		this.aggregatorService = aggregatorService;
	}

	/**
	 * GET /api/ticker/{symbol}/overview
	 * Returns a comprehensive overview for the given stock symbol.
	 * 
	 * @param symbol Stock symbol (e.g., "AAPL")
	 * @return Ticker overview with company profile, quote, financials, OHLC data, and description
	 */
	@GetMapping("/{symbol}/overview")
	public Mono<ResponseEntity<TickerOverviewDto>> getOverview(@PathVariable String symbol) {
		return aggregatorService.getOverview(symbol)
				.map(overview -> ResponseEntity.ok(overview))
				.onErrorResume(SymbolNotFoundException.class, ex -> 
					Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
							.body((TickerOverviewDto) null)))
				.onErrorResume(ServiceUnavailableException.class, ex -> 
					Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
							.body((TickerOverviewDto) null)))
				.onErrorResume(Exception.class, ex -> 
					Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
							.body((TickerOverviewDto) null)));
	}
}

