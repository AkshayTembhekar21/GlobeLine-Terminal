# Stock Aggregator - Architecture Diagram

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           CLIENT / USER                                  │
│                    (Browser, Postman, curl, etc.)                        │
└──────────────────────────────┬──────────────────────────────────────────┘
                                │
                                │ HTTP GET /api/ticker/{symbol}/overview
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         API LAYER                                        │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  TickerController                                                │   │
│  │  - GET /api/ticker/{symbol}/overview                             │   │
│  │  - Error Handling (404, 503, 500)                                │   │
│  └──────────────────────────────┬───────────────────────────────────┘   │
└──────────────────────────────────┼───────────────────────────────────────┘
                                   │
                                   │ Mono<TickerOverviewDto>
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      SERVICE LAYER                                       │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  AggregatorService                                                │   │
│  │  - Cache Check (Caffeine - 30s TTL)                               │   │
│  │  - Parallel API Calls (Mono.zip)                                  │   │
│  │  - Data Normalization                                             │   │
│  │  - Cache Storage                                                  │   │
│  │  - Error Handling & Stale Cache Fallback                         │   │
│  └───────┬───────────────────────────────────────────────┬───────────┘   │
│          │                                                 │               │
│          │                                                 │               │
│          ▼                                                 ▼               │
│  ┌───────────────┐                              ┌──────────────────┐     │
│  │  OverviewMapper│                              │  Cache (Caffeine)│     │
│  │  - Maps Finnhub DTOs → Internal DTO          │  - 30s TTL       │     │
│  │  - Calculates Net Income                     │  - Max 1000 items│     │
│  │  - Transforms OHLC Data                      │                  │     │
│  │  - Volume Fallback Logic                      │                  │     │
│  └───────────────┘                              └──────────────────┘     │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ Mono<FinnhubCompanyProfileDto>
                                   │ Mono<FinnhubQuoteDto>
                                   │ Mono<FinnhubMetricsDto>
                                   │ Mono<FinnhubCandleDto>
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    CONNECTOR LAYER                                       │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  FinnhubClient                                                    │   │
│  │  - getCompanyProfile(symbol)                                      │   │
│  │  - getQuote(symbol)                                               │   │
│  │  - getMetrics(symbol)                                             │   │
│  │  - getCandles(symbol) [Optional - 403 handled]                   │   │
│  └──────────────────────────────┬───────────────────────────────────┘   │
└──────────────────────────────────┼───────────────────────────────────────┘
                                   │
                                   │ WebClient (Reactive)
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    CONFIGURATION LAYER                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  WebClientConfig                                                  │   │
│  │  - Base URL: https://finnhub.io/api/v1                           │   │
│  │  - Default Header: X-Finnhub-Token                               │   │
│  │  - Request Logging Filter                                         │   │
│  │  - Retry Filter (3 retries, exponential backoff)                  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  CacheConfig                                                      │   │
│  │  - Caffeine Cache                                                 │   │
│  │  - TTL: 30 seconds                                                │   │
│  │  - Max Size: 1000 entries                                         │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  FinnhubApiProperties                                             │   │
│  │  - baseUrl: from application.properties                         │   │
│  │  - key: from application.properties                              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────┼───────────────────────────────────────┘
                                   │
                                   │ HTTP Requests
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    EXTERNAL API                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Finnhub API (https://finnhub.io/api/v1)                          │   │
│  │                                                                    │   │
│  │  Endpoints Used:                                                   │   │
│  │  ├─ GET /stock/profile2?symbol={symbol}                           │   │
│  │  ├─ GET /quote?symbol={symbol}                                    │   │
│  │  ├─ GET /stock/metric?symbol={symbol}&metric=all                 │   │
│  │  └─ GET /stock/candle?symbol={symbol}&resolution=D&from=...&to=..│   │
│  │     (Premium - Returns 403 on free tier)                          │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

## Data Flow Diagram

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │
       │ 1. GET /api/ticker/AAPL/overview
       ▼
┌─────────────────────────────────────┐
│   TickerController                  │
│   - Extracts symbol from path       │
│   - Calls AggregatorService         │
└──────┬──────────────────────────────┘
       │
       │ 2. getOverview("AAPL")
       ▼
┌─────────────────────────────────────┐
│   AggregatorService                 │
│                                     │
│   Step 1: Check Cache               │
│   ├─ Cache Hit? → Return Cached    │
│   └─ Cache Miss? → Continue        │
│                                     │
│   Step 2: Parallel API Calls        │
│   ├─ Mono.zip(                     │
│   │    profile,                    │
│   │    quote,                      │
│   │    metrics,                    │
│   │    candles                     │
│   │  )                             │
│                                     │
│   Step 3: Normalize Data           │
│   ├─ OverviewMapper.mapToOverview()│
│   │  ├─ Map company profile        │
│   │  ├─ Map quote data             │
│   │  ├─ Map financial metrics      │
│   │  ├─ Transform OHLC candles     │
│   │  ├─ Calculate net income      │
│   │  └─ Fallback volume           │
│                                     │
│   Step 4: Cache & Return           │
│   ├─ Store in cache (30s TTL)     │
│   └─ Return TickerOverviewDto      │
└──────┬──────────────────────────────┘
       │
       │ 3. Parallel HTTP Calls
       ▼
┌─────────────────────────────────────┐
│   FinnhubClient                     │
│   - Uses WebClient (Reactive)       │
│   - Applies filters (log, retry)    │
│   - Returns Mono<DTO>               │
└──────┬──────────────────────────────┘
       │
       │ 4. HTTP GET Requests
       ▼
┌─────────────────────────────────────┐
│   Finnhub API                       │
│   - Returns JSON responses          │
│   - WebClient deserializes to DTOs  │
└─────────────────────────────────────┘
```

## Component Details

### 1. API Layer
- **TickerController**: REST endpoint handler
  - Route: `/api/ticker/{symbol}/overview`
  - Returns: `Mono<ResponseEntity<TickerOverviewDto>>`
  - Error handling: 404 (not found), 503 (unavailable), 500 (server error)

### 2. Service Layer
- **AggregatorService**: Business logic orchestrator
  - Cache-first strategy (30-second TTL)
  - Parallel API calls using `Mono.zip()`
  - Error handling with stale cache fallback
  - Data aggregation and normalization

### 3. Mapper Layer
- **OverviewMapper**: Data transformation
  - Maps Finnhub DTOs → Internal DTO
  - Calculates derived fields (net income)
  - Transforms OHLC arrays to structured objects
  - Volume fallback logic

### 4. Connector Layer
- **FinnhubClient**: External API client
  - 4 methods for different endpoints
  - Reactive (non-blocking) using WebClient
  - Error translation (404 → SymbolNotFoundException)

### 5. Configuration Layer
- **WebClientConfig**: HTTP client configuration
  - Base URL and authentication
  - Request/response filters
  - Retry logic for rate limiting

- **CacheConfig**: In-memory caching
  - Caffeine cache implementation
  - 30-second TTL for overview data
  - Maximum 1000 entries

### 6. DTO Layer
- **Finnhub DTOs**: External API response models
  - `FinnhubCompanyProfileDto`
  - `FinnhubQuoteDto`
  - `FinnhubMetricsDto`
  - `FinnhubCandleDto`

- **Internal DTOs**: Application response models
  - `TickerOverviewDto`: Complete overview response
  - `OhlcData`: Single OHLC data point

## Request Flow Sequence

```
1. Client Request
   └─> GET /api/ticker/AAPL/overview

2. TickerController
   └─> AggregatorService.getOverview("AAPL")

3. AggregatorService
   ├─> Check Cache (Caffeine)
   │   └─> Cache Hit? Return immediately
   │
   └─> Cache Miss? Continue:
       ├─> FinnhubClient.getCompanyProfile("AAPL")
       ├─> FinnhubClient.getQuote("AAPL")
       ├─> FinnhubClient.getMetrics("AAPL")
       └─> FinnhubClient.getCandles("AAPL") [Optional]

4. Parallel Execution (Mono.zip)
   ├─> All 4 calls execute simultaneously
   ├─> Wait for all to complete
   └─> Combine results into Tuple4

5. OverviewMapper
   ├─> Transform Finnhub DTOs → TickerOverviewDto
   ├─> Calculate net income from margin
   ├─> Transform OHLC arrays
   └─> Apply volume fallback

6. Cache & Return
   ├─> Store in cache (30s TTL)
   └─> Return TickerOverviewDto

7. TickerController
   └─> Serialize to JSON → HTTP 200 Response
```

## Technology Stack

```
┌─────────────────────────────────────────┐
│         Spring Boot 3.5.8               │
│  ┌───────────────────────────────────┐ │
│  │  Spring WebFlux (Reactive)         │ │
│  │  - Netty Server                     │ │
│  │  - Non-blocking I/O                 │ │
│  └───────────────────────────────────┘ │
│  ┌───────────────────────────────────┐ │
│  │  Spring Data JPA (Future)          │ │
│  │  - PostgreSQL                       │ │
│  │  - Flyway Migrations                │ │
│  └───────────────────────────────────┘ │
│  ┌───────────────────────────────────┐ │
│  │  Caffeine Cache                    │ │
│  │  - In-memory caching               │ │
│  │  - 30s TTL                         │ │
│  └───────────────────────────────────┘ │
│  ┌───────────────────────────────────┐ │
│  │  Reactor (Project Reactor)        │ │
│  │  - Mono/Flux reactive types       │ │
│  │  - Parallel execution             │ │
│  └───────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

## Error Handling Flow

```
┌─────────────────────────────────────────┐
│  Error Scenarios                        │
├─────────────────────────────────────────┤
│                                         │
│  1. Symbol Not Found (404)             │
│     └─> SymbolNotFoundException        │
│         └─> HTTP 404 Response          │
│                                         │
│  2. Provider API Failure                │
│     ├─> Check Stale Cache              │
│     │   ├─> Found? Return stale data  │
│     │   └─> Not Found?                │
│     └─> ServiceUnavailableException    │
│         └─> HTTP 503 Response          │
│                                         │
│  3. Rate Limiting (429)                 │
│     └─> Retry Filter (3 retries)      │
│         └─> Exponential backoff        │
│                                         │
│  4. Candles API 403 (Premium)          │
│     └─> Return empty OHLC data         │
│         └─> Continue with other data   │
│                                         │
└─────────────────────────────────────────┘
```

## Future Enhancements (Not Yet Implemented)

```
┌─────────────────────────────────────────┐
│  Database Layer (Future)                │
│  ┌───────────────────────────────────┐ │
│  │  PostgreSQL                       │ │
│  │  ├─ companies table               │ │
│  │  └─ snapshots table               │ │
│  └───────────────────────────────────┘ │
│                                         │
│  ┌───────────────────────────────────┐ │
│  │  JPA Repositories                 │ │
│  │  ├─ CompanyRepository             │ │
│  │  └─ SnapshotRepository            │ │
│  └───────────────────────────────────┘ │
│                                         │
│  ┌───────────────────────────────────┐ │
│  │  Flyway Migrations                │ │
│  │  └─ V1__create_tables.sql         │ │
│  └───────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

## Key Design Patterns

1. **Reactive Programming**: Non-blocking I/O using Project Reactor
2. **Caching Strategy**: Cache-aside pattern with TTL
3. **Circuit Breaker**: Stale cache fallback on failures
4. **Parallel Execution**: `Mono.zip()` for concurrent API calls
5. **Dependency Injection**: Spring's IoC container
6. **DTO Pattern**: Separation of external and internal data models
7. **Mapper Pattern**: Data transformation layer

## Performance Characteristics

- **Non-blocking**: All I/O operations are asynchronous
- **Parallel API Calls**: 4 endpoints called simultaneously
- **Caching**: 30-second cache reduces API calls
- **Retry Logic**: Automatic retry on rate limits (429)
- **Error Resilience**: Stale cache fallback on failures

