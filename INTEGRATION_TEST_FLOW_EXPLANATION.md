# Integration Test Flow - Complete Technical Explanation

This document explains in depth how our integration tests work, tracing the complete flow from HTTP request to response. Think of this as a debugging session where we examine each step together.

## Table of Contents

1. [The Setup - Building Our Test Environment](#the-setup)
2. [The Test Execution Begins](#test-execution)
3. [Spring Web Framework Routes the Request](#routing)
4. [Controller Calls the Service Layer](#controller-service)
5. [Core Service Checks Cache and Prepares API Calls](#cache-check)
6. [The Parallel API Calls](#parallel-calls)
7. [MockWebServer Receives and Responds](#mock-server)
8. [WebClient Receives Responses and Deserializes JSON](#webclient-response)
9. [Core Service Combines Data and Caches Result](#combine-data)
10. [The Response Flows Back Through the Layers](#response-flow)
11. [Spring Serializes to JSON and Sends HTTP Response](#serialization)
12. [WebTestClient Validates the Response](#validation)
13. [Why This Matters - Real-World Significance](#significance)

---

## Stage 1: The Setup - Building Our Test Environment {#the-setup}

Before our test runs, Spring Boot prepares the entire application. Look at this annotation at the top of our test class:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
```

This annotation tells Spring Boot to load the entire application context - it's like starting your application for real, but just for testing. The `webEnvironment = RANDOM_PORT` part means Spring will start a real web server on a random port (like 54321) so tests don't collide with each other. It's starting a mini-version of your application just for testing purposes.

Next, we have this special configuration class inside our test:

```java
@TestConfiguration
static class MockWebServerConfiguration {
```

The `@TestConfiguration` annotation is Spring's way of saying "this configuration is only for testing, don't use it in the real application." Inside this class, we do something clever - we create a fake HTTP server that pretends to be the Finnhub API.

Here's how we create it:

```java
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
```

MockWebServer is essentially a fake web server. When it receives an HTTP request, instead of going to the real internet, it looks at the request path and returns a pre-written response. The `Dispatcher` is the brain that decides which response to return based on the URL path.

Why do we need a dispatcher instead of just enqueueing responses in order? Because our service makes four HTTP requests in parallel. If requests arrive in a different order than we enqueued responses, they would get mismatched. The dispatcher looks at each request's path and returns the matching response, so the order doesn't matter.

Then we do something important - we replace the real WebClient with one that points to our fake server:

```java
@Bean
@Primary
public WebClient finnhubWebClient(FinnhubApiProperties properties, MockWebServer mockWebServer) {
    String baseUrl = mockWebServer.url("/").toString();
    if (baseUrl.endsWith("/")) {
        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    
    return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Finnhub-Token", properties.key())
            .build();
}
```

The `@Primary` annotation tells Spring "when someone asks for a WebClient bean, give them this one instead of the real one from WebClientConfig." This is called bean overriding - we're replacing the production configuration with our test configuration.

---

## Stage 2: The Test Execution Begins - Making the HTTP Request {#test-execution}

When we execute this line in our test:

```java
webTestClient.get()
        .uri("/api/ticker/AAPL/overview")
        .exchange()
```

WebTestClient is Spring's special tool for testing web applications. It doesn't just call a method - it makes a real HTTP request to your running application. It's like using curl or Postman, but automated.

When `webTestClient.get().uri("/api/ticker/AAPL/overview").exchange()` executes, here's what happens:

WebTestClient constructs an actual HTTP GET request: `GET http://localhost:54321/api/ticker/AAPL/overview`. It sends this over the network (even if it's just localhost), and Spring's web framework receives it. This is a real HTTP request, not a method call - you could capture it with a network monitor if you wanted.

---

## Stage 3: Spring Web Framework Routes the Request {#routing}

Spring WebFlux (the reactive web framework we're using) receives this HTTP request. It looks at the URL pattern and finds our controller method:

```java
@RestController
@RequestMapping("/api/ticker")
public class TickerController {
    
    @GetMapping("/{symbol}/overview")
    public Mono<ResponseEntity<TickerOverviewDto>> getOverview(@PathVariable String symbol) {
        return aggregatorService.getOverview(symbol)
                .map(overview -> ResponseEntity.ok(overview))
                .onErrorResume(SymbolNotFoundException.class, ex -> 
                    Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body((TickerOverviewDto) null)))
                .onErrorResume(ServiceUnavailableException.class, ex -> 
                    Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body((TickerOverviewDto) null)));
    }
}
```

The `@GetMapping("/{symbol}/overview")` annotation tells Spring "when you see a GET request to `/api/ticker/{something}/overview`, call this method." The `{symbol}` part is a path variable - Spring extracts "AAPL" from the URL and passes it as the `symbol` parameter.

Notice that this method returns `Mono<ResponseEntity<TickerOverviewDto>>`. A `Mono` is a container from Project Reactor that represents a value that will arrive later. It's like a promise or future - "I don't have the answer yet, but I'll have it soon." The reactive programming model means we don't block threads waiting for results - we set up a pipeline of operations that execute asynchronously.

---

## Stage 4: Controller Calls the Service Layer {#controller-service}

The controller calls `aggregatorService.getOverview(symbol)`. Let's see what happens inside `AggregatorService`:

```java
public Mono<TickerOverviewDto> getOverview(String symbol) {
    String upperSymbol = symbol.toUpperCase();
    
    // Start timing the entire operation
    Timer.Sample sample = metrics.startTimer();
    
    // Delegate to core service - it knows nothing about metrics
    Mono<TickerOverviewDto> result = coreService.getOverview(upperSymbol);
    
    // Wrap the Mono with metrics instrumentation
    return result
            .doOnSuccess(overview -> {
                logger.debug("Successfully completed aggregation for symbol: {}", upperSymbol);
            })
            .doOnError(SymbolNotFoundException.class, ex -> {
                metrics.recordSymbolNotFound();
            })
            .doOnError(ServiceUnavailableException.class, ex -> {
                metrics.recordServiceUnavailable();
            })
            .doFinally(signalType -> {
                metrics.stopTimer(sample);
            });
}
```

This is the Decorator Pattern in action. `AggregatorService` wraps `CoreAggregatorService` and adds metrics. It doesn't change the business logic - it just adds timing and error tracking around it.

The `doOnSuccess`, `doOnError`, and `doFinally` methods are Reactor operators. They're like callbacks that Reactor will execute at specific points in the reactive stream. Think of it like setting up event listeners - "when this succeeds, do this. When this errors, do that. When this finishes (no matter how), do this other thing."

When we call `coreService.getOverview(upperSymbol)`, we're now entering the business logic layer.

---

## Stage 5: Core Service Checks Cache and Prepares API Calls {#cache-check}

Inside `CoreAggregatorService.getOverview()`, the first thing it does is check the cache:

```java
TickerOverviewDto cached = overviewCache.getIfPresent(upperSymbol);
if (cached != null) {
    logger.debug("Cache hit for symbol: {}", upperSymbol);
    if (eventObserver != null) {
        eventObserver.onCacheHit();
    }
    return Mono.just(cached);
}
```

The cache check is synchronous - it's a simple lookup in memory. If we find the data, we immediately return it wrapped in `Mono.just()`, which means "here's a Mono that already contains this value, no waiting needed."

In our test scenario, the cache is empty (it's a fresh Spring context), so we continue. The service then checks if there's already an in-flight request for this symbol:

```java
Mono<TickerOverviewDto> inFlight = inFlightRequests.get(upperSymbol);
if (inFlight != null) {
    logger.debug("In-flight request found for symbol: {}, sharing the same operation", upperSymbol);
    if (eventObserver != null) {
        eventObserver.onInFlightSharing();
    }
    return inFlight;
}
```

This prevents duplicate API calls. If another request for "AAPL" is already in progress, we share that same `Mono` instead of making a new API call. Since this is the first request, there's nothing in-flight, so we continue.

---

## Stage 6: The Parallel API Calls - Where the Magic Happens {#parallel-calls}

Now we reach the most interesting part - making the API calls. Look at this code:

```java
Mono<FinnhubCandleDto> candlesMono = finnhubClient.getCandles(upperSymbol)
        .onErrorResume(ex -> {
            logger.warn("Failed to fetch candles for symbol: {} - continuing without OHLC data", upperSymbol);
            return Mono.just(new FinnhubCandleDto(null, null, null, null, null, null, "error"));
        });

Mono<TickerOverviewDto> fetchOperation = Mono.zip(
        finnhubClient.getCompanyProfile(upperSymbol),
        finnhubClient.getQuote(upperSymbol),
        finnhubClient.getMetrics(upperSymbol),
        candlesMono)
```

The `Mono.zip()` operator is crucial here. It says "wait for all four of these Monos to complete, then combine their results into a tuple." The key word here is "wait for all" - it doesn't care about the order they complete, just that all four finish.

Each of these four calls (`getCompanyProfile`, `getQuote`, `getMetrics`, `getCandles`) returns a `Mono`, which means "I'll have the answer eventually, but not right now." These four calls happen in parallel - Reactor doesn't wait for one to finish before starting the next. It sends all four HTTP requests simultaneously and waits for all of them to respond.

Let's trace what happens when `finnhubClient.getCompanyProfile("AAPL")` is called. Looking at the `FinnhubClient` code:

```java
public Mono<FinnhubCompanyProfileDto> getCompanyProfile(String symbol) {
    return finnhubWebClient.get()
            .uri(uriBuilder -> uriBuilder
                    .path("/stock/profile2")
                    .queryParam("symbol", symbol)
                    .build())
            .retrieve()
            .bodyToMono(FinnhubCompanyProfileDto.class)
            .onErrorMap(WebClientResponseException.NotFound.class,
                    ex -> new SymbolNotFoundException("Symbol not found: " + symbol, ex));
}
```

The `finnhubWebClient` is the WebClient we created in our test configuration. Remember, it's pointing to our MockWebServer, not the real Finnhub API. So when we call `finnhubWebClient.get().uri(...)`, it's actually making an HTTP request to `http://localhost:54321/stock/profile2?symbol=AAPL` - our fake server.

WebClient is Spring's reactive HTTP client. The `.get()` method starts building a GET request. The `.uri()` method sets the URL path and query parameters. The `.retrieve()` method says "execute this request and give me the response." The `.bodyToMono(FinnhubCompanyProfileDto.class)` part means "convert the JSON response body into a `FinnhubCompanyProfileDto` object."

But here's the important part - all of this is non-blocking. When we call these methods, we're not waiting for the HTTP request to complete. We're just setting up a pipeline of operations that will execute asynchronously. The actual HTTP request happens in the background, and Reactor handles all the networking.

---

## Stage 7: MockWebServer Receives and Responds {#mock-server}

When WebClient sends the HTTP request to `http://localhost:54321/stock/profile2?symbol=AAPL`, our MockWebServer receives it. The dispatcher we set up earlier looks at the request:

```java
public MockResponse dispatch(RecordedRequest request) {
    String path = request.getPath();
    
    if (path != null) {
        if (path.contains("/stock/profile2")) {
            return createCompanyProfileResponse();
        }
        // ... other paths
    }
}
```

The `request.getPath()` returns `/stock/profile2?symbol=AAPL`. Our dispatcher checks if it contains `/stock/profile2` - yes it does! So it calls `createCompanyProfileResponse()`:

```java
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
```

This creates a mock HTTP response with status code 200 (success) and a JSON body. MockWebServer sends this response back to WebClient.

Similarly, the other three requests (quote, metrics, candles) arrive at MockWebServer, and the dispatcher matches each one to its response based on the path. All four requests are happening simultaneously, and MockWebServer handles them as they arrive.

---

## Stage 8: WebClient Receives Responses and Deserializes JSON {#webclient-response}

When WebClient receives the HTTP response from MockWebServer, it needs to convert the JSON string into a Java object. The `.bodyToMono(FinnhubCompanyProfileDto.class)` part we saw earlier does exactly that.

Jackson (the JSON library Spring uses) reads the JSON string and creates a `FinnhubCompanyProfileDto` object. For example, it sees `"name": "Apple Inc."` and sets the `name` field of our DTO to "Apple Inc."

Once all four responses are received and converted to objects, `Mono.zip()` combines them into a tuple. A tuple is like a container that holds multiple values in order - tuple.getT1() gets the first value (profile), tuple.getT2() gets the second (quote), and so on.

---

## Stage 9: Core Service Combines Data and Caches Result {#combine-data}

Back in `CoreAggregatorService`, after `Mono.zip()` completes, we have all four pieces of data. The `.map()` operator transforms this tuple into our final `TickerOverviewDto`:

```java
.map(tuple -> {
    var profile = tuple.getT1();
    var quote = tuple.getT2();
    var metrics = tuple.getT3();
    var candles = tuple.getT4();

    // Normalize and combine data
    TickerOverviewDto overview = mapper.mapToOverview(profile, quote, metrics, candles);

    // Store in cache
    overviewCache.put(upperSymbol, overview);

    logger.info("Successfully aggregated overview for symbol: {}", upperSymbol);
    return overview;
})
```

The `mapper.mapToOverview()` method takes the four separate DTOs and combines them into a single unified overview. This is where we transform Finnhub's format into our own format. For example, Finnhub might call it "name" but we want "companyName", or Finnhub gives us separate fields but we want a combined description.

After creating the overview, we store it in the cache so the next request for "AAPL" will be much faster.

---

## Stage 10: The Response Flows Back Through the Layers {#response-flow}

Now we have a `TickerOverviewDto` object. This flows back through the layers:

First, it goes through `AggregatorService`, where the `doOnSuccess` callback executes and logs the success. The timer we started earlier gets stopped in `doFinally`.

Then it reaches the `TickerController`, where we wrap it in a `ResponseEntity`:

```java
.map(overview -> ResponseEntity.ok(overview))
```

A `ResponseEntity` is Spring's way of representing an HTTP response - it includes the status code (200 OK in this case), headers, and the body. `ResponseEntity.ok()` is a shortcut that creates a 200 OK response with the body we provide.

---

## Stage 11: Spring Serializes to JSON and Sends HTTP Response {#serialization}

Spring WebFlux takes this `ResponseEntity<TickerOverviewDto>` and converts it into an actual HTTP response. It serializes the `TickerOverviewDto` object back to JSON using Jackson. The response looks like this:

```
HTTP/1.1 200 OK
Content-Type: application/json

{
    "companyName": "Apple Inc.",
    "sector": "Technology",
    "country": "US",
    "currentPrice": 150.25,
    "change": 2.50,
    "percentChange": 1.69,
    ...
}
```

This HTTP response travels back through the network to WebTestClient.

---

## Stage 12: WebTestClient Validates the Response {#validation}

Finally, WebTestClient receives the HTTP response and our test assertions check it:

```java
.expectStatus().isOk()
.expectBody()
.jsonPath("$.companyName").isEqualTo("Apple Inc.")
.jsonPath("$.sector").isEqualTo("Technology")
```

The `.expectStatus().isOk()` checks that the HTTP status code is 200. The `.jsonPath("$.companyName")` uses JSONPath (like XPath for JSON) to navigate the JSON structure and extract the `companyName` field, then compares it to "Apple Inc."

If all assertions pass, the test succeeds.

---

## Why This Matters - The Real-World Significance {#significance}

This integration test is useful because it verifies the entire chain works together:

1. **HTTP routing works correctly** - Spring knows which controller method to call
2. **Dependency injection works** - All the services are wired together correctly
3. **The reactive programming model works** - The non-blocking, parallel execution behaves as expected
4. **Error handling works** - Exceptions flow through the layers correctly
5. **JSON serialization/deserialization works** - Data transforms correctly at each boundary

In a unit test, we might test each piece separately, but we wouldn't catch issues like "the JSON field name doesn't match what the client expects" or "Spring isn't injecting the dependencies correctly." Integration tests catch those real-world integration issues.

The reactive programming part is particularly important. When we use `Mono.zip()` with parallel requests, we're using non-blocking I/O. This means our application thread doesn't sit idle waiting for the HTTP responses. It can handle other requests while waiting. This is what makes reactive applications scalable - one thread can handle thousands of concurrent requests because it's never blocked waiting for I/O.

---

## Key Concepts Explained

### Reactive Programming with Mono

A `Mono` is a container that represents a value that will arrive in the future. It's like a promise or future. When you call a method that returns a `Mono`, you're not getting the result immediately - you're getting a container that will eventually contain the result.

This allows non-blocking I/O - your thread doesn't wait for the HTTP request to complete. It can do other work while waiting. When the response arrives, Reactor executes the next step in your pipeline.

### Mono.zip() - Parallel Execution

`Mono.zip()` waits for all provided Monos to complete, then combines their results. Since the Monos execute independently, they run in parallel. This means all four API calls happen simultaneously, not one after another.

### MockWebServer Dispatcher - Path-Based Routing

Instead of serving responses in FIFO order (first in, first out), the dispatcher matches responses to requests based on the URL path. This is essential when requests arrive in parallel and in unpredictable order.

### Bean Overriding with @Primary

In Spring, when you have multiple beans of the same type, `@Primary` marks one as the preferred choice. In our test, we override the production WebClient with a test version that points to MockWebServer.

### Test Isolation with @DirtiesContext

`@DirtiesContext` tells Spring to reload the application context after each test method. This ensures tests don't interfere with each other - each test gets a fresh cache and clean state.

---

## Visual Flow Diagram

```
[WebTestClient]
    |
    | HTTP GET /api/ticker/AAPL/overview
    |
[Spring WebFlux Router]
    |
    | Routes to TickerController.getOverview()
    |
[TickerController]
    |
    | Calls aggregatorService.getOverview("AAPL")
    |
[AggregatorService] (Decorator)
    |
    | Starts timer
    | Calls coreService.getOverview("AAPL")
    |
[CoreAggregatorService]
    |
    | Checks cache (miss)
    | Checks in-flight (none)
    |
    | Creates 4 parallel API calls:
    |   - getCompanyProfile()
    |   - getQuote()
    |   - getMetrics()
    |   - getCandles()
    |
[FinnhubClient] (4 instances in parallel)
    |
    | Each makes HTTP request via WebClient
    |
[MockWebServer] (Receives 4 requests)
    |
    | Dispatcher matches each request to response by path:
    |   - /stock/profile2 → Company Profile JSON
    |   - /quote → Quote JSON
    |   - /stock/metric → Metrics JSON
    |   - /stock/candle → Candles JSON
    |
[WebClient] (Receives 4 responses)
    |
    | Deserializes each JSON to DTO object
    |
[Mono.zip()] (Combines 4 results)
    |
    | All 4 responses received → Combines into tuple
    |
[CoreAggregatorService]
    |
    | Maps tuple to TickerOverviewDto
    | Stores in cache
    | Returns Mono<TickerOverviewDto>
    |
[AggregatorService] (Decorator)
    |
    | Records metrics
    | Stops timer
    | Returns Mono<TickerOverviewDto>
    |
[TickerController]
    |
    | Wraps in ResponseEntity
    | Handles errors if any
    | Returns Mono<ResponseEntity<TickerOverviewDto>>
    |
[Spring WebFlux]
    |
    | Serializes to JSON
    | Creates HTTP response
    |
[WebTestClient]
    |
    | Receives HTTP 200 OK with JSON body
    | Validates status code and JSON fields
    |
[Test Passes ✅]
```

---

## Conclusion

Integration tests verify that all the pieces of your application work together correctly. They test the full stack - from HTTP request routing, through business logic, to external API calls, and back to the HTTP response. While unit tests verify individual components work in isolation, integration tests verify that those components work together as a complete system.

