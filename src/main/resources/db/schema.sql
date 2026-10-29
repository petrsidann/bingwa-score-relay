-- ============================================================
-- Bingwa Relay · Postgres schema (Relay 13)
-- Versioned + idempotent: safe to apply at every boot.
-- Applied by PostgresStore ONLY when DB_URL is set; otherwise
-- the server runs on InMemoryStore and this file is unused.
-- ============================================================

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER PRIMARY KEY,
    name        TEXT        NOT NULL,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- v1 ----------------------------------------------------------------
INSERT INTO schema_version (version, name) VALUES (1, 'relay_13_core')
ON CONFLICT (version) DO NOTHING;

CREATE TABLE IF NOT EXISTS users (
    user_id       TEXT PRIMARY KEY,
    phone         TEXT UNIQUE NOT NULL,
    access_token  TEXT,
    refresh_token TEXT,
    expires_at    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    status        TEXT NOT NULL DEFAULT 'pending_verification',   -- pending_verification | active | suspended | deleted
    verified_at   TIMESTAMPTZ
);
ALTER TABLE users ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS otps (
    phone      TEXT PRIMARY KEY,
    code       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS offers (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    price       DOUBLE PRECISION NOT NULL CHECK (price >= 0),
    verified    BOOLEAN NOT NULL DEFAULT true,
    agent_slug  TEXT,                              -- per-agent storefront (Relay 15)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS coupons (
    id           TEXT PRIMARY KEY,
    code         TEXT UNIQUE NOT NULL,
    offer_id     TEXT REFERENCES offers(id),
    discount_pct DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (discount_pct BETWEEN 0 AND 100),
    max_uses     INTEGER NOT NULL DEFAULT 1,
    used         INTEGER NOT NULL DEFAULT 0,
    active       BOOLEAN NOT NULL DEFAULT true,
    expires_at   TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS features (
    id          TEXT PRIMARY KEY,
    code        TEXT UNIQUE NOT NULL,
    name        TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS passes (
    id         TEXT PRIMARY KEY,
    feature_id TEXT NOT NULL REFERENCES features(id),
    user_id    TEXT NOT NULL REFERENCES users(user_id),
    active     BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS messages (
    id      TEXT PRIMARY KEY,
    from_id TEXT NOT NULL,
    to_id   TEXT NOT NULL,
    body    TEXT NOT NULL,
    at_ms   BIGINT
);

CREATE TABLE IF NOT EXISTS transactions (
    id                  TEXT PRIMARY KEY,
    user_id             TEXT,
    agent_slug          TEXT,
    offer_id            TEXT REFERENCES offers(id),
    amount              DOUBLE PRECISION NOT NULL,
    currency            TEXT NOT NULL DEFAULT 'NGN',
    status              TEXT NOT NULL DEFAULT 'pending',     -- pending | paid | failed | refunded
    checkout_request_id TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS flags (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS audit (
    id     BIGSERIAL PRIMARY KEY,
    at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    action TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS agents (
    slug       TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    owner_phone TEXT,
    active     BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes -----------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_offers_agent   ON offers(agent_slug);
CREATE INDEX IF NOT EXISTS idx_passes_user    ON passes(user_id);
CREATE INDEX IF NOT EXISTS idx_tx_agent       ON transactions(agent_slug);
CREATE INDEX IF NOT EXISTS idx_tx_status      ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_messages_to    ON messages(to_id);

-- Seed demo data (idempotent, ON CONFLICT DO NOTHING) ---------------
INSERT INTO offers (id, title, price, verified) VALUES
    ('o1','Starter Pack',5.0,true), ('o2','Pro Season',20.0,true)
ON CONFLICT (id) DO NOTHING;
INSERT INTO features (id, code, name, description) VALUES
    ('f1','LIVE_SCORES','Live Scores','Real-time match scores'),
    ('f2','AIRTIME','Airtime Rewards','Earn airtime')
ON CONFLICT (id) DO NOTHING;
INSERT INTO flags (key, value, enabled) VALUES
    ('live_scores','true',true), ('airtime_rewards','true',true), ('push_enabled','false',true)
ON CONFLICT (key) DO NOTHING;
