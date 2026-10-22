# Bingwa Score Relay

High-performance Kotlin JVM 17 + Ktor 3 score relay server, admin dashboard, storefront generator, and M-Pesa Daraja payment integration.

---

## Features

- **Relay Core**: Ktor 3 WebSocket & SSE event bus for real-time scores and app state synchronization.
- **Admin Portal**: SPA shell served at `/admin` (Tailwind, Chart.js, htmx) with metrics, kill switch, flags, and reconciliation.
- **Store Generator**: Per-agent mobile-first HTML storefronts at `/store/{slug}` and JSON offer feeds at `/store/{slug}/offers`.
- **M-Pesa Payments**: Daraja STK Push integration (`/api/payments/stk`) with callback processing (`/api/payments/callback`), sandbox simulation, and manual reconciliation.
- **Hardening & CI**: Rate limiting, CORS, Request-ID logging, multi-stage Docker build, and GitHub Actions CI.

---

## Environment Variables Reference Table

| Variable | Default / Fallback | Description |
| :--- | :--- | :--- |
| `PORT` | `8080` | HTTP port |
| `DB_URL` | *(Unset = InMemoryStore)* | Postgres JDBC URL (e.g., `jdbc:postgresql://localhost:5432/bingwa`) |
| `RELAY_SERVER_KEY` | *(Unset = unguarded `/api/*`)* | Secret server key for guarding `/api/*` routes |
| `ADMIN_KEY` | Falls back to `RELAY_SERVER_KEY` | Secret key for guarding `/admin/*` operations |
| `CORS_ORIGINS` | *(Unset = no CORS)* | Comma-separated list of allowed CORS origins |
| `RATE_LIMIT_AUTH_PER_MIN` | `30` | Requests allowed per minute per IP for `/api/auth/*` and `/api/payments/*` |
| `DARAJA_CONSUMER_KEY` | *(Unset = Sandbox mode)* | Safaricom Daraja M-Pesa Consumer Key |
| `DARAJA_CONSUMER_SECRET` | *(Unset)* | Safaricom Daraja M-Pesa Consumer Secret |
| `DARAJA_SHORTCODE` | `174379` | Safaricom M-Pesa Shortcode |
| `DARAJA_PASSKEY` | *(Unset)* | Safaricom M-Pesa Passkey |
| `DARAJA_ENV` | `sandbox` | M-Pesa environment (`sandbox` or `live`) |
| `SEED_DEMO` | `true` | Seed demo data on startup when DB URL is set |

---

## Local Development & Running

### 1. Requirements
- JDK 17 (Eclipse Temurin / OpenJDK 17)
- Gradle wrapper (`./gradlew`)

### 2. Build & Run
```bash
# Run tests
./gradlew test

# Compile Kotlin
./gradlew compileKotlin

# Run application locally on port 8080
./gradlew run
```

### 3. Docker Run
```bash
# Build Docker image
docker build -t bingwa-score-relay .

# Run container
docker run -p 8080:8080 -e ADMIN_KEY=secret -e RELAY_SERVER_KEY=secret bingwa-score-relay
```

---

## Key URLs & Endpoints

- **Health Check**: `http://localhost:8080/healthz`
- **Admin Console**: `http://localhost:8080/admin?key=secret`
- **Public Agent Storefront**: `http://localhost:8080/store/{slug}`
- **Agent Offers Feed**: `http://localhost:8080/store/{slug}/offers`
- **M-Pesa STK Push API**: `POST http://localhost:8080/api/payments/stk`
- **M-Pesa Webhook Callback**: `POST http://localhost:8080/api/payments/callback`
