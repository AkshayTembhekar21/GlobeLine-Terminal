package com.GlobeLine.stock_aggregator.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCandleDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubCompanyProfileDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubMetricsDto;
import com.GlobeLine.stock_aggregator.dto.finnhub.FinnhubQuoteDto;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import reactor.test.StepVerifier;

/**
 * Unit tests for FinnhubClient.
 * Uses MockWebServer to simulate Finnhub API responses without making real HTTP calls.
 */
class FinnhubClientTest {

	private MockWebServer mockWebServer;
	private FinnhubClient finnhubClient;
	private String baseUrl;

	@BeforeEach
	void setUp() throws Exception {
		// Start a mock web server to simulate Finnhub API
		mockWebServer = new MockWebServer();
		mockWebServer.start();
		
		baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
		
		// Create WebClient pointing to our mock server
		WebClient webClient = WebClient.builder()
				.baseUrl(baseUrl)
				.defaultHeader("X-Finnhub-Token", "test-token")
				.build();
		
		finnhubClient = new FinnhubClient(webClient);
	}

	@AfterEach
	void tearDown() throws Exception {
		// Shutdown mock server after each test
		mockWebServer.shutdown();
	}

	@Test
	void getCompanyProfile_Success() {
		// Arrange: Set up mock response
		String jsonResponse = """
				{
					"ticker": "AAPL",
					"name": "Apple Inc.",
					"country": "US",
					"currency": "USD",
					"exchange": "NASDAQ",
					"ipo": "1980-12-12",
					"marketCapitalization": 3000000000000,
					"shareOutstanding": 15000000000,
					"weburl": "https://www.apple.com",
					"logo": "https://logo.url",
					"finnhubIndustry": "Technology",
					"phone": "1-800-123-4567"
				}
				""";
		
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(jsonResponse)
				.addHeader("Content-Type", "application/json"));

		// Act & Assert
		StepVerifier.create(finnhubClient.getCompanyProfile("AAPL"))
				.assertNext(profile -> {
					assertThat(profile.ticker()).isEqualTo("AAPL");
					assertThat(profile.name()).isEqualTo("Apple Inc.");
					assertThat(profile.country()).isEqualTo("US");
					assertThat(profile.website()).isEqualTo("https://www.apple.com");
				})
				.verifyComplete();
	}

	@Test
	void getCompanyProfile_NotFound_ThrowsException() {
		// Arrange: Mock 404 response
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(404)
				.setBody("Not Found"));

		// Act & Assert
		StepVerifier.create(finnhubClient.getCompanyProfile("INVALID"))
				.expectErrorMatches(error -> 
					error instanceof FinnhubClient.SymbolNotFoundException &&
					error.getMessage().contains("Symbol not found: INVALID"))
				.verify();
	}

	@Test
	void getQuote_Success() {
		// Arrange
		String jsonResponse = """
				{
					"c": 150.25,
					"d": 2.50,
					"dp": 1.69,
					"h": 151.00,
					"l": 149.50,
					"o": 150.00,
					"pc": 147.75,
					"t": 1640995200,
					"v": 50000000
				}
				""";
		
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(jsonResponse)
				.addHeader("Content-Type", "application/json"));

		// Act & Assert
		StepVerifier.create(finnhubClient.getQuote("AAPL"))
				.assertNext(quote -> {
					assertThat(quote.currentPrice()).isEqualByComparingTo(new BigDecimal("150.25"));
					assertThat(quote.change()).isEqualByComparingTo(new BigDecimal("2.50"));
					assertThat(quote.percentChange()).isEqualByComparingTo(new BigDecimal("1.69"));
				})
				.verifyComplete();
	}

	@Test
	void getMetrics_Success() {
		// Arrange
		String jsonResponse = """
				{
					"metricType": "all",
					"metric": {
						"revenuePerShareTTM": 23.5,
						"netProfitMarginTTM": 0.25,
						"peTTM": 28.5,
						"dividendYieldIndicatedAnnual": 0.005
					}
				}
				""";
		
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(jsonResponse)
				.addHeader("Content-Type", "application/json"));

		// Act & Assert
		StepVerifier.create(finnhubClient.getMetrics("AAPL"))
				.assertNext(metrics -> {
					assertThat(metrics.metricType()).isEqualTo("all");
					assertThat(metrics.metricValue("revenuePerShareTTM"))
							.isEqualByComparingTo(new BigDecimal("23.5"));
					assertThat(metrics.metricValue("peTTM"))
							.isEqualByComparingTo(new BigDecimal("28.5"));
				})
				.verifyComplete();
	}

	@Test
	void getCandles_Success() {
		// Arrange
		String jsonResponse = """
				{
					"c": [150.0, 151.0, 152.0],
					"o": [149.0, 150.5, 151.5],
					"h": [151.0, 152.0, 153.0],
					"l": [148.0, 149.5, 150.5],
					"t": [1640995200, 1641081600, 1641168000],
					"v": [1000000, 1100000, 1200000],
					"s": "ok"
				}
				""";
		
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(jsonResponse)
				.addHeader("Content-Type", "application/json"));

		// Act & Assert
		StepVerifier.create(finnhubClient.getCandles("AAPL"))
				.assertNext(candles -> {
					assertThat(candles.status()).isEqualTo("ok");
					assertThat(candles.close()).hasSize(3);
					assertThat(candles.open()).hasSize(3);
					assertThat(candles.isOk()).isTrue();
				})
				.verifyComplete();
	}

	@Test
	void getQuote_ServerError_PropagatesException() {
		// Arrange: Mock 500 error
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(500)
				.setBody("Internal Server Error"));

		// Act & Assert
		StepVerifier.create(finnhubClient.getQuote("AAPL"))
				.expectError(WebClientResponseException.class)
				.verify();
	}
}

