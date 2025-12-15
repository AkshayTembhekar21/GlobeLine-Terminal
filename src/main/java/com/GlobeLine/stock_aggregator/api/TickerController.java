package com.GlobeLine.stock_aggregator.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.GlobeLine.stock_aggregator.connectors.FinnhubClient.SymbolNotFoundException;
import com.GlobeLine.stock_aggregator.dto.TickerOverviewDto;
import com.GlobeLine.stock_aggregator.exception.RateLimitExceededException;
import com.GlobeLine.stock_aggregator.exception.ServiceUnavailableException;
import com.GlobeLine.stock_aggregator.service.AggregatorService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/ticker")
@Tag(name = "Ticker", description = "Stock ticker overview endpoints")
public class TickerController {

	private final AggregatorService aggregatorService;

	public TickerController(AggregatorService aggregatorService) {
		this.aggregatorService = aggregatorService;
	}

	@Operation(
			summary = "Get ticker overview",
			description = "Returns a comprehensive overview for a stock symbol including company profile, " +
					"real-time quote data, financial metrics, 1-month OHLC data for charting, and company description. " +
					"All data is aggregated from Finnhub API and cached for 30 seconds.")
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Successfully retrieved ticker overview",
					content = @Content(schema = @Schema(implementation = TickerOverviewDto.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Symbol not found",
					content = @Content),
			@ApiResponse(
					responseCode = "429",
					description = "Too many requests - rate limit exceeded (100 requests per minute per IP)",
					content = @Content),
			@ApiResponse(
					responseCode = "503",
					description = "Service temporarily unavailable (may return stale cached data if available)",
					content = @Content),
			@ApiResponse(
					responseCode = "500",
					description = "Internal server error",
					content = @Content)
	})
	@GetMapping("/{symbol}/overview")
	public Mono<ResponseEntity<TickerOverviewDto>> getOverview(
			@Parameter(description = "Stock symbol (e.g., AAPL, MSFT, GOOGL)", required = true, example = "AAPL")
			@PathVariable String symbol) {
		return aggregatorService.getOverview(symbol)
				.map(overview -> ResponseEntity.ok(overview))
				.onErrorResume(RateLimitExceededException.class, ex -> 
					Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
							.body((TickerOverviewDto) null)))
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

