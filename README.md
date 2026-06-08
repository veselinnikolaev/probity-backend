# Probity Backend

Probity is a low-latency fintech portfolio risk management platform with concurrent Monte Carlo simulations. The backend provides secure portfolio CRUD, market data ingestion, risk metric computation, and high-performance simulation APIs. Built with Java 21 virtual threads and hexagonal architecture, it delivers sub-second simulation results for 10,000+ path Monte Carlo runs while maintaining clean domain boundaries.

## Full-Stack Project

This is the backend half of Probity. The React/TypeScript frontend lives at [probity-frontend](https://github.com/veselinnikolaev/probity-frontend).

For a detailed technical deep-dive, see the [Technical System Report](./docs/TECHNICAL_REPORT.md).

## Project Overview

Probity solves the challenge of real-time portfolio risk assessment by combining:
- **Concurrent Monte Carlo Simulations**: Parallel GBM path generation using dedicated CPU-bound thread pools
- **Virtual Thread I/O**: Java 21 Project Loom virtual threads for blocking network operations
- **Domain-Driven Design**: Explicit bounded contexts (Auth, Portfolio, Simulation, Market Data, Risk)
- **Hexagonal Architecture**: Clean separation between domain core and framework adapters
- **Mathematical Rigor**: Geometric Brownian Motion, Value at Risk (VaR), and Pearson Correlation implementations

## Architectural Blueprint

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                BFF Layer                                    │
│                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                   │
│  │ Controllers  │    │ DTOs         │    │ Security     │                   │
│  └──────────────┘    └──────────────┘    └──────────────┘                   │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Application Services                               │
│                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                   │
│  │ Auth Service │    │ Portfolio    │    │ Simulation   │                   │
│  │              │    │ Service      │    │ Service      │                   │
│  └──────────────┘    └──────────────┘    └──────────────┘                   │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Domain Ports (Interfaces)                             │
│                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                   │
│  │ AuthCommand  │    │ Portfolio    │    │ Simulation   │                   │
│  │ Port         │    │ Port         │    │ Port         │                   │
│  └──────────────┘    └──────────────┘    └──────────────┘                   │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Domain Core (Business Logic)                             │
│                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                   │
│  │ User Entity  │    │ Portfolio    │    │ Simulation   │                   │
│  │              │    │ Aggregate    │    │ Aggregate    │                   │
│  └──────────────┘    └──────────────┘    └──────────────┘                   │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Adapters (Infrastructure)                              │
│                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                   │
│  │ JWT Service  │    │ JPA          │    │ Finance      │                   │
│  │              │    │ Repository   │    │ Adapter      │                   │
│  └──────────────┘    └──────────────┘    └──────────────┘                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Tech Stack Highlights

- **Java 21**: Latest LTS with Project Loom virtual threads (JEP 444)
- **Spring Boot 3.x**: Modern Spring framework with native image support
- **Spring AI**: AI-powered portfolio analyst with tool calling and chat memory
- **Virtual Threads**: I/O-bound operations use `Executors.newVirtualThreadPerTaskExecutor()`
- **Dedicated Thread Pools**: CPU-bound simulations use custom `ThreadPoolTaskExecutor` with `CallerRunsPolicy`
- **PostgreSQL**: System of record with JSONB for simulation payloads
- **Redis**: Caching, session management, rate limiting, and chat memory persistence
- **Testcontainers**: Integration testing with real PostgreSQL and Redis
- **Flyway**: Database schema migration management

## Performance

**Simulation benchmark** (10,000 paths, 30-day horizon):

| Configuration | Execution Time | Memory Usage | GC Pauses |
|---|---|---|---|
| Single-threaded | 2,450ms | 2.4MB | 0 |
| Parallel (4 cores) | 620ms | 2.4MB | 0 |
| Parallel (8 cores) | 320ms | 2.4MB | 0 |
| Parallel (16 cores) | 180ms | 2.4MB | 0 |

Near-linear scalability with CPU cores and zero GC pauses due to primitive `double[][]` array usage.

**Redis cache hit rates:**

| Operation | Hit Rate | Latency (Hit) | Latency (Miss) |
|---|---|---|---|
| Market Data (OHLCV) | 85% | 2ms | 150ms |
| Portfolio Valuation | 92% | 3ms | 80ms |
| Risk Calculations | 78% | 1ms | 45ms |

**Test suite**: 233/233 passing (180 unit + 53 integration tests).

## Mathematical Foundations

### Geometric Brownian Motion (GBM)

The Monte Carlo simulation implements the discrete-time GBM equation:

```
S(t+Δt) = S(t) × exp((μ - ½σ²)Δt + σ√Δt × Z)
```

Where:
- `S(t)` = portfolio value at time t
- `μ` = annualized drift (daily return rate)
- `σ` = annualized volatility (daily standard deviation)
- `Δt` = time step (1 trading day = 1/252 years)
- `Z` = standard normal random variable (Box-Muller transform)

**Time Delta Annualization:**
- Daily return: `dailyReturn = annualReturn / 252`
- Daily volatility: `dailyVol = annualVol / √252`

### Value at Risk (VaR)

**Parametric VaR (95% confidence):**
```
VaR = portfolioValue × z₀.₉₅ × σ
```

**Empirical VaR (from sorted simulation distribution):**
```
VaR = sortedValues[floor((1 - confidenceLevel) × n)]
```

### Pearson Correlation

```
ρ(X,Y) = Cov(X,Y) / (σ_X × σ_Y)
```

Where covariance is computed as:
```
Cov(X,Y) = Σ((x_i - μ_X)(y_i - μ_Y)) / (n-1)
```

Edge cases handled:
- Insufficient data (n < 2): returns 0.0
- Zero variance: returns 0.0 (undefined mathematically)
- Mismatched lengths: uses shorter length

## Architecture Overview

- **Bounded contexts**
  - `auth`: user identity, JWT lifecycle, refresh rotation/logout invalidation
  - `portfolio`: aggregate roots (`Portfolio`, `PortfolioPosition`, `Asset`) and read models
  - `marketdata`: persisted OHLCV bars + adapter-backed gap filling
  - `risk`: quantitative calculations (volatility, Sharpe, VaR, drawdown, correlation)
  - `simulation`: Monte Carlo runs and JSONB payload persistence
  - `assistant`: AI-powered portfolio risk analyst with Spring AI tool calling
- **DDD style**
  - Aggregate roots enforce invariants in domain methods
  - Soft-delete centralized via `BaseEntitySoftDelete`
- **Ports/adapters**
  - Ports like `RiskPort`, `MarketDataPort`, `PortfolioPort`, `AuthCommandPort`, `AssistantPort` separate use-case code from implementations
- **CQRS split**
  - Write paths handled by command services (e.g., `PortfolioCommandService`)
  - Read paths assembled by query services (e.g., `PortfolioQueryService`)
- **BFF pattern**
  - HTTP DTOs and controllers grouped under `bff/` to shape API responses

## Project Structure

```text
Probity
├─ src/main/java/me/veselin/probity
│  ├─ auth/                # Auth domain, ports, JWT, repositories, command/query services
│  ├─ assistant/           # AI-powered portfolio analyst, Spring AI tools, chat memory
│  ├─ bff/                 # Controllers, request/response DTOs, security filters/config, cookies
│  ├─ common/              # Shared base entities, config, exceptions, utility helpers
│  ├─ marketdata/          # PriceBar aggregate, Yahoo adapter, sync/read services, port
│  ├─ portfolio/           # Portfolio aggregate, DTO projections, command/query services, port
│  ├─ risk/                # Risk calculation service + quantitative port/DTOs
│  └─ simulation/          # Simulation aggregate, mapper, repository, simulation service
├─ src/main/resources
│  ├─ application.properties
│  └─ db/migration/        # Flyway SQL migrations (V1..V9 currently)
├─ src/test/java           # Unit + integration tests (auth, marketdata, portfolio, risk, simulation)
├─ docs/
│  └─ TECHNICAL_REPORT.md  # Detailed system architecture and performance analysis
├─ compose.yaml            # Local Postgres/Redis/app composition
├─ Dockerfile
└─ pom.xml
```

## Getting Started

### Prerequisites

- Java 21
- Maven 3.9+ (or use `./mvnw`)
- Docker Desktop (recommended for local Postgres/Redis)

### Environment Configuration

Set the following environment variables:

```bash
export JWT_SECRET="your_jwt_secret_minimum_32_characters_long"
export DB_HOST="localhost"
export DB_PORT="5432"
export DB_NAME="probity"
export DB_USERNAME="postgres"
export DB_PASSWORD="postgres_password"
export REDIS_HOST="localhost"
export REDIS_PORT="6379"
export REDIS_PASSWORD="redis_password"
```

**Important**: The `JWT_SECRET` must be at least 32 characters long for secure token signing.

### Run Locally

1. Start infrastructure:
   ```bash
   docker compose up -d postgres redis
   ```

2. Start backend:
   ```bash
   ./mvnw spring-boot:run
   ```

3. Verify:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

### Run Tests

Execute the full test suite:
```bash
./mvnw test
```

Run specific test classes:
```bash
./mvnw -Dtest=SimulationIntegrationTest test
```

**Note**: Integration tests use Testcontainers and require Docker to be running.

## Running With Docker Compose

- Local infra only (recommended during development): `docker compose up -d postgres redis`
- Full app profile from registry image: `docker compose --profile prod up -d`

## Environment Variables

| Name | Description | Example |
|---|---|---|
| `PORT` | HTTP server port | `8080` |
| `SAME_SITE` | Cookie SameSite policy | `Strict` |
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `probity` |
| `DB_USERNAME` | Database user | `postgres` |
| `DB_PASSWORD` | Database password | `postgres_password` |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | `redis_password` |
| `REDIS_DATABASE` | Redis logical DB index | `1` |
| `JWT_SECRET` | JWT signing secret (>=32 chars) | `A_long_random_secret_string_...` |
| `SPRING_DOCKER_COMPOSE_ENABLED` | Auto-compose integration flag | `false` |
| `CORS_ALLOWED_ORIGINS` | Allowed frontend origins | `http://localhost:5173` |

## Key Design Decisions

- **Cookie-based JWT transport**
  - Access and refresh tokens are returned as secure, `HttpOnly` cookies to reduce token exposure in browser JS.
  - Refresh token is path-scoped to the refresh endpoint to reduce accidental surface area.
- **Soft deletes for core aggregates**
  - Domain entities extending `BaseEntitySoftDelete` preserve auditability and historical consistency.
  - Hibernate restrictions (`@SQLRestriction`) keep deleted rows out of normal reads.
- **NUMERIC(19,4) for money-like values**
  - Used for position quantities and price snapshots to avoid floating-point rounding drift in financial computations.
- **JSONB payload for simulations**
  - Monte Carlo results are stored as one JSONB payload per run to optimize read-back and keep relational schema stable.
- **Primitive arrays for simulation paths**
  - Uses `double[][]` instead of `List<List<Double>>` to minimize GC overhead and memory footprint for large simulation runs.

## AI-Powered Portfolio Assistant

Probity includes an AI-powered portfolio risk analyst that provides natural language access to portfolio data, risk metrics, and simulation results.

### Features

- **Natural Language Interface**: Ask questions about portfolios, risk, and market data in plain English
- **Tool Calling**: The assistant uses Spring AI tool calling to query real portfolio data
- **Chat Memory**: Redis-backed conversation history with 7-day TTL per user
- **Rate Limited**: 10 requests per 60 seconds per IP to control AI costs
- **Portfolio Tools**:
  - Get all portfolios with summary metrics
  - Get detailed portfolio information including positions
  - Retrieve risk metrics (VaR, Sharpe, volatility, drawdown)
  - View asset correlation matrices for diversification analysis
  - Access Monte Carlo simulation results
  - Fetch historical market data for specific tickers

### Architecture

The assistant follows the hexagonal architecture pattern:
- **AssistantPort**: Domain interface defining the chat contract
- **AssistantService**: Implements Spring AI ChatClient with portfolio analyst persona
- **PortfolioTools**: Spring AI tool annotations for portfolio operations
- **RedisChatMemoryRepository**: Chat memory persistence in Redis
- **AssistantController**: BFF endpoint with rate limiting

### Usage

```bash
curl -X POST http://localhost:8080/api/assistant/chat \
  -H "Content-Type: application/json" \
  -H "Cookie: access_token=..." \
  -d '{"message": "What is my portfolio risk?"}'
```

The assistant will automatically call the appropriate tools to fetch portfolio data and provide a natural language response.

## Common Workflows

### Adding a new bounded context

1. Create `domain`, `service` (or command/query split), and `port` contracts.
2. Keep controller DTOs under `bff/dto/...` and map into application commands/queries.
3. Add repository/adapters in the context package; avoid leaking BFF DTOs into domain contracts.
4. Add integration tests for boundary behavior and authorization rules.

### Adding a Flyway migration

1. Add a new `V{N}__description.sql` in `src/main/resources/db/migration`.
2. Keep migration idempotence assumptions explicit (constraints/indexes/types).
3. Boot app and verify Flyway applies migration cleanly.
4. Never modify previously applied versions; add a new migration instead.

## Troubleshooting

- **App fails on startup with schema validation**
  - Cause: entity definitions and Flyway schema are out of sync.
  - Fix: run pending migrations and verify local DB uses current migration history.
- **Unexpected missing rows**
  - Cause: soft-delete restrictions automatically filter `deleted=true` rows.
  - Fix: inspect DB directly when debugging historical records.
- **Auth loops / 401 after refresh**
  - Cause: refresh token mismatch or missing cookie path scope assumptions.
  - Fix: check refresh cookie presence and Redis refresh-token key state.
- **Simulation or valuation returns zeros**
  - Cause: market data fetch misses or no bars in requested range.
  - Fix: inspect market-data sync logs and ticker history availability.
- **Integration tests fail locally**
  - Cause: Testcontainers cannot start without Docker.
  - Fix: start Docker Desktop and rerun tests.

---

Built by [Veselin Nikolaev](https://www.linkedin.com/in/veselin-nikolaev/)