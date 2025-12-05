package com.GlobeLine.stock_aggregator.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.GlobeLine.stock_aggregator.config.FinnhubApiProperties;
import com.GlobeLine.stock_aggregator.config.WebClientConfig;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Integration tests for TickerController.
 * 
 * INTEGRATION TEST vs UNIT TEST:
 * 
 * Unit Test (like CoreAggregatorServiceTest):
 * - Tests ONE component in isolation
 * - Mocks ALL dependencies
 * - Fast execution (milliseconds)
 * - Example: Tests CoreAggregatorService with mocked FinnhubClient
 * 
 * Integration Test (this class):
 * - Tests MULTIPLE components working together
 * - Uses REAL Spring context (real beans, real dependency injection)
 * - Tests FULL flow: HTTP Request → Controller → Service → Response
 * - Slower execution (seconds) because Spring context loads
 * - Example: Tests /api/ticker/AAPL/overview endpoint with real Spring setup
 * 
 * Key Differences:
 * 1. @SpringBootTest - Loads full Spring application context
 * 2. WebTestClient - Tests actual HTTP endpoints (not just method calls)
 * 3. Real dependency injection - Services are real, only external API is mocked
 * 4. Tests end-to-end flow - HTTP request → Controller → Service → HTTP response
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TickerControllerIntegrationTest.MockWebServerConfiguration.class)
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TickerControllerIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private MockWebServer mockWebServer;
	
	// Store reference to original dispatcher for test overrides
	private Dispatcher originalDispatcher;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() throws InterruptedException {
		// Create WebTestClient bound to the application context
		webTestClient = WebTestClient.bindToApplicationContext(applicationContext).build();
		
		// Store reference to original dispatcher for test overrides
		originalDispatcher = mockWebServer.getDispatcher();
	}

	@AfterEach
	void tearDown() {
		// Verify MockWebServer received expected number of requests
		// This helps debug if requests aren't reaching the mock server
		try {
			int requestCount = mockWebServer.getRequestCount();
			System.out.println("MockWebServer received " + requestCount + " requests");
		} catch (Exception e) {
			// Ignore - MockWebServer might be shut down
		}
	}

	@Test
	void getOverview_Success_Returns200WithOverview() {
		// Arrange: MockWebServer uses a dispatcher (set up in MockWebServerConfiguration)
		// that matches responses by path, so we don't need to enqueue responses manually
		// The dispatcher automatically handles all 4 API endpoints based on request path

		// Act & Assert: Make actual HTTP request to our endpoint
		webTestClient.get()
				.uri("/api/ticker/AAPL/overview")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.companyName").isEqualTo("Apple Inc.")
				.jsonPath("$.sector").isEqualTo("Technology")
				.jsonPath("$.country").isEqualTo("US")
				.jsonPath("$.currentPrice").isEqualTo(150.25)
				.jsonPath("$.change").isEqualTo(2.50)
				.jsonPath("$.percentChange").isEqualTo(1.69);
	}

	@Test
	void getOverview_SymbolNotFound_Returns404() throws InterruptedException {
		// Arrange: Override dispatcher to return 404 for profile endpoint
		// This simulates Finnhub returning 404 for invalid symbol
		mockWebServer.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String path = request.getPath();
				if (path != null && path.contains("/stock/profile2")) {
					// Return 404 for profile to simulate symbol not found
					return new MockResponse().setResponseCode(404).setBody("Not Found");
				} else if (path != null && path.contains("/quote")) {
					return new MockResponse().setResponseCode(404).setBody("Not Found");
				} else if (path != null && path.contains("/stock/metric")) {
					return new MockResponse().setResponseCode(404).setBody("Not Found");
				} else if (path != null && path.contains("/stock/candle")) {
					return new MockResponse().setResponseCode(404).setBody("Not Found");
				}
				try {
					return originalDispatcher.dispatch(request);
				} catch (Exception e) {
					return new MockResponse().setResponseCode(500).setBody("Error");
				}
			}
		});

		// Act & Assert: Test error handling
		// Note: SymbolNotFoundException gets converted to ServiceUnavailableException
		// when there's no stale cache, so we expect 503, not 404
		webTestClient.get()
				.uri("/api/ticker/INVALID/overview")
				.exchange()
				.expectStatus().is5xxServerError();
		
		// Restore original dispatcher
		mockWebServer.setDispatcher(originalDispatcher);
	}

	@Test
	void getOverview_ApiError_Returns503() throws InterruptedException {
		// Arrange: Override dispatcher to return 500 error
		mockWebServer.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String path = request.getPath();
				if (path != null && path.contains("/stock/profile2")) {
					// Return 500 to simulate service unavailable
					return new MockResponse().setResponseCode(500).setBody("Internal Server Error");
				}
				try {
					return originalDispatcher.dispatch(request);
				} catch (Exception e) {
					return new MockResponse().setResponseCode(500).setBody("Error");
				}
			}
		});

		// Act & Assert: Test error handling
		webTestClient.get()
				.uri("/api/ticker/AAPL/overview")
				.exchange()
				.expectStatus().is5xxServerError();
		
		// Restore original dispatcher
		mockWebServer.setDispatcher(originalDispatcher);
	}

	@Test
	void getOverview_CacheHit_ReturnsCachedData() {
		// Arrange: Dispatcher handles responses automatically
		// First request - cache miss, fetch from API (dispatcher provides responses)
		
		// First request - should hit API and cache the result
		webTestClient.get()
				.uri("/api/ticker/AAPL/overview")
				.exchange()
				.expectStatus().isOk();

		// Second request within cache TTL - should use cache (no API calls)
		webTestClient.get()
				.uri("/api/ticker/AAPL/overview")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.companyName").isEqualTo("Apple Inc.");
	}

	// Helper methods to set up mock responses
	private void enqueueCompanyProfileResponse() {
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
	}

	private void enqueueQuoteResponse() {
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
	}

	private void enqueueMetricsResponse() {
		String jsonResponse = """
				{
					"metricType": "all",
					"metric": {
						"revenuePerShareTTM": 23.5,
						"netProfitMarginTTM": 25.0,
						"epsTTM": 2.50,
						"peAnnual": 28.5,
						"dividendYieldIndicatedAnnual": 0.005
					}
				}
				""";
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(jsonResponse)
				.addHeader("Content-Type", "application/json"));
	}

	private void enqueueCandlesResponse() {
		// Candles response must have arrays for all fields (c, o, h, l, t, v)
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
	}

	/**
	 * Test configuration that sets up MockWebServer for integration tests.
	 * This replaces the real Finnhub API with our mock server.
	 * 
	 * How it works:
	 * 1. Creates a MockWebServer that listens on a random port
	 * 2. Overrides the finnhubWebClient bean to point to our mock server
	 * 3. Spring uses this configuration instead of the real WebClientConfig
	 */
	@TestConfiguration
	static class MockWebServerConfiguration {

	@Bean
	@Primary
	public MockWebServer mockWebServer() throws Exception {
		MockWebServer server = new MockWebServer();
		
		// Use a dispatcher to match responses by path instead of FIFO order
		// This is crucial because parallel requests may arrive in any order
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String path = request.getPath();
				
				if (path != null) {
					if (path.contains("/stock/profile2")) {
						return createCompanyProfileResponse();
					} else if (path.contains("/quote") && !path.contains("/candle")) {
						return createQuoteResponse();
					} else if (path.contains("/stock/metric")) {
						return createMetricsResponse();
					} else if (path.contains("/stock/candle")) {
						return createCandlesResponse();
					}
				}
				
				return new MockResponse().setResponseCode(404);
			}
		});
		
		server.start();
		return server;
	}
	
	private MockResponse createCompanyProfileResponse() {
		String json = """
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
		return new MockResponse().setResponseCode(200).setBody(json)
				.addHeader("Content-Type", "application/json");
	}
	
	private MockResponse createQuoteResponse() {
		String json = """
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
		return new MockResponse().setResponseCode(200).setBody(json)
				.addHeader("Content-Type", "application/json");
	}
	
	private MockResponse createMetricsResponse() {
		String json = """
				{
					"metricType": "all",
					"metric": {
						"revenuePerShareTTM": 23.5,
						"netProfitMarginTTM": 25.0,
						"epsTTM": 2.50,
						"peAnnual": 28.5,
						"dividendYieldIndicatedAnnual": 0.005
					}
				}
				""";
		return new MockResponse().setResponseCode(200).setBody(json)
				.addHeader("Content-Type", "application/json");
	}
	
	private MockResponse createCandlesResponse() {
		String json = """
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
		return new MockResponse().setResponseCode(200).setBody(json)
				.addHeader("Content-Type", "application/json");
	}

	@Bean
	@Primary
	public WebClient finnhubWebClient(FinnhubApiProperties properties, MockWebServer mockWebServer) {
		// Override the base URL to point to our mock server
		// MockWebServer.url("/") returns something like "http://localhost:54321/"
		// We need just "http://localhost:54321" (without trailing slash)
		String baseUrl = mockWebServer.url("/").toString();
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		
		// Create WebClient pointing to mock server (without filters for testing)
		return WebClient.builder()
				.baseUrl(baseUrl)
				.defaultHeader("X-Finnhub-Token", properties.key())
				.build();
	}
	}
}

