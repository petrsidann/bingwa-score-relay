# Deploying Bingwa Relay to Render (Free Tier)

This guide provides step-by-step instructions to deploy Bingwa Relay on Render using the included multi-stage `Dockerfile` and managed Render Postgres database.

---

## 1. Create a Managed Render Postgres Database

1. Log in to [Render Dashboard](https://dashboard.render.com/).
2. Click **New +** > **PostgreSQL**.
3. Configure the database settings:
   - **Name**: `bingwa-relay-db`
   - **Database Name**: `bingwa_relay`
   - **User**: `bingwa_user`
   - **Region**: Choose your preferred region (e.g. Frankfurt or Oregon).
   - **Instance Type**: **Free**.
4. Click **Create Database**.
5. Once created, copy the **Internal Database URL** (e.g., `postgres://bingwa_user:...@dpg-...-a:5432/bingwa_relay`).

---

## 2. Deploy Bingwa Relay Web Service

1. On the Render Dashboard, click **New +** > **Web Service**.
2. Connect your Git repository (`petrsidann/bingwa-score-relay`).
3. Configure Web Service settings:
   - **Name**: `bingwa-score-relay`
   - **Region**: Same region as your database.
   - **Branch**: `main`
   - **Runtime**: **Docker**.
   - **Dockerfile Path**: `Dockerfile`
   - **Instance Type**: **Free**.

---

## 3. Configure Environment Variables

Under **Environment Variables** in your Web Service settings, add the following key-value pairs:

| Variable | Recommended Production Value | Description |
| :--- | :--- | :--- |
| `PORT` | `8080` | Application HTTP listening port |
| `DB_URL` | `jdbc:postgresql://dpg-...-a:5432/bingwa_relay` | JDBC URL for Render Postgres |
| `RELAY_SERVER_KEY` | `<your-generated-server-secret>` | Secret key for guarding `/api/*` routes |
| `ADMIN_KEY` | `<your-generated-admin-secret>` | Secret key for guarding Admin Console `/admin` |
| `CORS_ORIGINS` | `https://your-frontend.onrender.com` | Allowed cross-origin domains (comma-separated) |
| `SEED_DEMO` | `false` | Turn OFF demo seeding in production |
| `DARAJA_CONSUMER_KEY` | `<mpesa-consumer-key>` | Safaricom Daraja M-Pesa Consumer Key |
| `DARAJA_CONSUMER_SECRET` | `<mpesa-consumer-secret>` | Safaricom Daraja M-Pesa Consumer Secret |
| `DARAJA_SHORTCODE` | `174379` | Safaricom Shortcode (Paybill/Till) |
| `DARAJA_PASSKEY` | `<mpesa-passkey>` | Safaricom STK Push Passkey |
| `DARAJA_ENV` | `live` (or `sandbox`) | M-Pesa environment mode |

---

## 4. Deploy & Verify

1. Click **Create Web Service**.
2. Render will build the image via `Dockerfile` and start the container.
3. Verify deployment status:
   - **Healthcheck**: Render checks `http://localhost:8080/healthz` (returns `ok`).
   - **Admin Portal**: Open `https://bingwa-score-relay.onrender.com/admin?key=<ADMIN_KEY>`.
   - **Storefront**: Open `https://bingwa-score-relay.onrender.com/store/<slug>`.
