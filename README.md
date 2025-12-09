# GlobeLine Terminal - Stock Aggregator

A Spring Boot reactive application that aggregates stock market data from Finnhub API and provides a unified overview endpoint for stock tickers.

## Features

- **Company Profile**: Name, sector, country, website
- **Live Quote**: Real-time price, change, day high/low, volume, market cap
- **Financial Snapshot**: Revenue TTM, net income TTM, EPS, P/E ratio, dividend yield
- **1-Month OHLC Data**: Daily candlestick data for charting
- **Company Description**: Auto-generated company information

## Tech Stack

- **Java 17**
- **Spring Boot 3.5.8**
- **Spring WebFlux** (Reactive/Non-blocking)
- **Spring Data JPA**
- **PostgreSQL** (for future persistence)
- **Flyway** (Database migrations)
- **Caffeine Cache** (In-memory caching)
- **SpringDoc OpenAPI** (API documentation with Swagger UI)
- **Maven**

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Docker and Docker Compose (for local PostgreSQL infrastructure)
- Finnhub API key ([Get one here](https://finnhub.io/))

## Setup

### 1. Clone the Repository

```bash
git clone https://github.com/AkshayTembhekar21/GlobeLine-Terminal.git
cd stock-aggregator/stock-aggregator
```

### 2. Configure API Key

The Finnhub API key is configured in `src/main/resources/application.properties`:

```properties
finnhub.api.base-url=https://finnhub.io/api/v1
finnhub.api.key=your-api-key-here
```

**Note**: For production, consider using environment variables or AWS Secrets Manager instead of hardcoding the key.

### 3. Start Local Infrastructure (Optional)

For local development with PostgreSQL, use Docker Compose:

```bash
docker-compose up -d
```

This starts:
- **PostgreSQL** on port `5432`
  - Database: `stock_aggregator`
  - Username: `stock_user`
  - Password: `stock_password`
- **pgAdmin** on port `5050` (optional database management UI)
  - Email: `admin@stockaggregator.local`
  - Password: `admin`

To stop the infrastructure:

```bash
docker-compose down
```

To stop and remove all data:

```bash
docker-compose down -v
```

**Note**: The application currently has database auto-configuration disabled. When you're ready to use persistence, update `application.properties` to enable database connections.

### 4. Run the Application

```bash
mvn spring-boot:run
```

Or using the Maven wrapper:

```bash
./mvnw spring-boot:run
```

The application will start on `http://localhost:8080`

## API Documentation

### Interactive API Documentation (Swagger UI)

Once the application is running, you can access the interactive API documentation at:

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8080/api-docs`

The Swagger UI provides:
- Interactive API explorer to test endpoints directly from the browser
- Complete request/response schemas
- Example values and parameter descriptions
- Try-it-out functionality to execute API calls

### API Endpoints

### Get Ticker Overview

Returns a comprehensive overview for a stock symbol.

**Endpoint:** `GET /api/ticker/{symbol}/overview`

**Path Parameters:**
- `symbol` (required): Stock symbol (e.g., AAPL, MSFT, GOOGL)

**Example Request:**
```bash
curl http://localhost:8080/api/ticker/AAPL/overview
```

**Example Response:**
```json
{
  "companyName": "Apple Inc",
  "sector": "Technology",
  "country": "US",
  "website": "https://www.apple.com",
  "currentPrice": 175.50,
  "change": 2.30,
  "percentChange": 1.33,
  "dayHigh": 176.20,
  "dayLow": 173.80,
  "volume": 45000000,
  "marketCap": 2800000000000,
  "revenueTTM": 394328000000,
  "netIncomeTTM": 99803000000,
  "eps": 6.11,
  "peRatio": 28.7,
  "dividendYield": 0.52,
  "ohlcData": [
    {
      "timestamp": 1696118400,
      "open": 175.20,
      "high": 176.50,
      "low": 174.80,
      "close": 175.50,
      "volume": 45000000
    }
  ],
  "description": "Apple Inc is a company in the Technology industry based in US.",
  "lastUpdated": "2024-11-24T10:30:00Z"
}
```

**Response Codes:**
- `200 OK`: Success
- `404 NOT FOUND`: Invalid stock symbol
- `503 SERVICE UNAVAILABLE`: Provider API temporarily unavailable (may return stale cached data)
- `500 INTERNAL SERVER ERROR`: Unexpected error

## Caching

The application uses in-memory caching (Caffeine) with the following TTLs:

- **Overview**: 30 seconds (live data changes frequently)
- **Financials**: 24 hours (changes less frequently)
- **Candles**: 5-15 minutes (depending on resolution)

Cache metrics (hits/misses) are logged for monitoring.

## Project Structure

```
src/main/java/com/GlobeLine/stock_aggregator/
├── api/                    # REST Controllers
│   └── TickerController.java
├── service/                # Business Logic
│   └── AggregatorService.java
├── connectors/             # External API Clients
│   └── FinnhubClient.java
├── dto/                    # Data Transfer Objects
│   ├── finnhub/           # Finnhub API DTOs
│   ├── TickerOverviewDto.java
│   └── OhlcData.java
├── mapper/                 # Data Normalization
│   └── OverviewMapper.java
├── config/                 # Configuration Classes
│   ├── WebClientConfig.java
│   ├── CacheConfig.java
│   ├── FinnhubApiProperties.java
│   └── OpenApiConfig.java
└── exception/              # Custom Exceptions
    └── ServiceUnavailableException.java
```

## Finnhub API Endpoints Used

1. **Company Profile**: `GET /stock/profile2?symbol={symbol}`
2. **Real-time Quote**: `GET /quote?symbol={symbol}`
3. **Financial Metrics**: `GET /stock/metric?symbol={symbol}&metric=all`
4. **Stock Candles**: `GET /stock/candle?symbol={symbol}&resolution=D&from={timestamp}&to={timestamp}`

All requests are made in parallel for optimal performance.

## Error Handling

- **Invalid Symbol**: Returns 404 with appropriate message
- **Provider Failure**: Returns 503, attempts to serve stale cached data if available
- **Rate Limiting**: Automatic retry with exponential backoff (3 retries, max 2s backoff)

## Development

### Building the Project

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

## Local Development Infrastructure

The project includes a `docker-compose.yml` file for local PostgreSQL setup:

- **PostgreSQL 16** (Alpine-based, lightweight)
- **pgAdmin 4** (Database management UI)
- **Health checks** for service readiness
- **Persistent volumes** for data storage

See the "Start Local Infrastructure" section above for usage instructions.

## Future Enhancements

- [ ] Enable database persistence (PostgreSQL infrastructure ready)
- [ ] Redis cache for distributed caching
- [ ] Additional data providers
- [ ] WebSocket support for real-time updates
- [ ] Frontend dashboard
- [ ] Comprehensive test coverage

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
