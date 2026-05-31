# Probity Backend

Probity is a portfolio management and risk analytics platform. The backend provides secure portfolio CRUD, market data ingestion, risk metric computation, and Monte Carlo simulation APIs for the frontend. It is designed around explicit domain boundaries so portfolio analytics can evolve independently from auth, market-data synchronization, and simulation workloads.

The service exposes BFF-oriented endpoints under `bff/`, while domain/application logic lives in bounded contexts (`portfolio`, `marketdata`, `risk`, `simulation`, `auth`). PostgreSQL is the system of record, Redis is used for caching/session artifacts/rate-limit state, and Flyway owns schema evolution.

## Architecture Overview

- **Bounded contexts**
  - `auth`: user identity, JWT lifecycle, refresh rotation/logout invalidation.
  - `portfolio`: aggregate roots (`Portfolio`, `PortfolioPosition`, `Asset`) and read models for dashboard/risk views.
  - `marketdata`: persisted OHLCV bars + adapter-backed gap filling.
  - `risk`: quantitative calculations (volatility, Sharpe, VaR, drawdown, correlation).
  - `simulation`: Monte Carlo runs and JSONB payload persistence.
- **DDD style**
  - Aggregate roots enforce invariants in domain methods.
  - Soft-delete is centralized via `BaseEntitySoftDelete`.
- **Ports/adapters**
  - Ports like `RiskPort`, `MarketDataPort`, `PortfolioPort`, `AuthCommandPort` separate use-case code from implementations.
- **CQRS split**
  - Write paths are handled by command services (for example `PortfolioCommandService`).
  - Read paths are assembled by query services (for example `PortfolioQueryService`).
- **BFF pattern**
  - HTTP DTOs and controllers are grouped under `bff/` to shape API responses for frontend consumption.

## Project Structure

```text
Probity
├─ src/main/java/me/veselin/probity
│  ├─ auth/                # Auth domain, ports, JWT, repositories, command/query services
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
├─ compose.yaml            # Local Postgres/Redis/app composition
├─ Dockerfile
└─ pom.xml
```

## Prerequisites

- Java 21
- Maven 3.9+ (or use `./mvnw`)
- Docker Desktop (recommended for local Postgres/Redis)

## Run Locally (15-minute path)

1. Copy env template:
   - `cp .env.example .env` (or create `.env` manually on Windows).
2. Fill required values in `.env` (`DB_*`, `REDIS_*`, `JWT_SECRET`).
3. Start infrastructure:
   - `docker compose up -d postgres redis`
4. Start backend:
   - `./mvnw spring-boot:run`
5. Verify:
   - Health endpoint: `GET http://localhost:8080/actuator/health`

Notes:
- Flyway runs automatically on boot.
- `spring.jpa.hibernate.ddl-auto=validate`, so schema must match migrations.

## Running With Docker Compose

- Local infra only (recommended during development): `docker compose up -d postgres redis`
- Full app profile from registry image: `docker compose --profile prod up -d`

## Test Execution

- Full test suite: `./mvnw test`
- Single test class: `./mvnw -Dtest=SimulationIntegrationTest test`
- Integration tests rely on Testcontainers dependencies in `pom.xml`; ensure Docker is running.

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
