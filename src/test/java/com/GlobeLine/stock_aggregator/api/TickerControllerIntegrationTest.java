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

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

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
@TestPropertySource(properties = {
		"finnhub.api.key=test-key",
		"spring.main.allow-bean-definition-overriding=true"
})
class TickerControllerIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private MockWebServer mockWebServer;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		// Create WebTestClient bound to the application context
		webTestClient = WebTestClient.bindToApplicationContext(applicationContext).build();
	}

	@AfterEach
	void tearDown() {
		// MockWebServer is a Spring bean - Spring will handle cleanup
		// Each test enqueues its own responses, so no cleanup needed
	}

	@Test
	void getOverview_Success_Returns200WithOverview() {
		// Arrange: Set up mock responses for all Finnhub endpoints
		enqueueCompanyProfileResponse();
		enqueueQuoteResponse();
		enqueueMetricsResponse();
		enqueueCandlesResponse();

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
	void getOverview_SymbolNotFound_Returns404() {
		// Arrange: Mock 404 response from Finnhub
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(404)
				.setBody("Not Found"));

		// Act & Assert: Test error handling
		webTestClient.get()
				.uri("/api/ticker/INVALID/overview")
				.exchange()
				.expectStatus().isNotFound();
	}

	@Test
	void getOverview_ApiError_Returns503() {
		// Arrange: Mock 500 error from Finnhub (simulating service unavailable)
		mockWebServer.enqueue(new MockResponse()
				.setResponseCode(500)
				.setBody("Internal Server Error"));

		// Act & Assert: Test error handling
		webTestClient.get()
				.uri("/api/ticker/AAPL/overview")
				.exchange()
				.expectStatus().is5xxServerError();
	}

	@Test
	void getOverview_CacheHit_ReturnsCachedData() {
		// Arrange: First request - cache miss, fetch from API
		enqueueCompanyProfileResponse();
		enqueueQuoteResponse();
		enqueueMetricsResponse();
		enqueueCandlesResponse();

		// First request - should hit API
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
			server.start();
			return server;
		}

		@Bean
		@Primary
		public WebClient finnhubWebClient(FinnhubApiProperties properties, MockWebServer mockWebServer) {
			// Override the base URL to point to our mock server
			String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
			
			// Create WebClient similar to WebClientConfig but pointing to mock server
			return WebClient.builder()
					.baseUrl(baseUrl)
					.defaultHeader("X-Finnhub-Token", properties.key())
					.build();
		}
	}
}

