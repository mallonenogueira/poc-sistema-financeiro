CREATE TABLE accounts (
    id           UUID PRIMARY KEY,
    holder_name  VARCHAR(120)   NOT NULL,
    document     VARCHAR(11)    NOT NULL UNIQUE,
    balance      NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    status       VARCHAR(20)    NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL,
    version      BIGINT         NOT NULL DEFAULT 0
);

CREATE TABLE transfers (
    id                UUID PRIMARY KEY,
    source_account_id UUID           NOT NULL REFERENCES accounts (id),
    target_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount            NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    fee               NUMERIC(19, 2) NOT NULL CHECK (fee >= 0),
    type              VARCHAR(10)    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    idempotency_key   VARCHAR(100)   NOT NULL UNIQUE,
    rejection_reason  VARCHAR(255),
    created_at        TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_transfers_source ON transfers (source_account_id, created_at DESC);
CREATE INDEX idx_transfers_target ON transfers (target_account_id, created_at DESC);

CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY,
    aggregate_id VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

-- Índice parcial: o relay só olha eventos pendentes.
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
