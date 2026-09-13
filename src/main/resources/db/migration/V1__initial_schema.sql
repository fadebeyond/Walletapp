-- Bearer tokens are stored hashed; a user may hold several (issuing one never invalidates the others).
CREATE TABLE api_tokens (
    token_hash TEXT        PRIMARY KEY,
    user_id    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_tokens_user_id ON api_tokens (user_id);

-- allow_negative marks the house float wallet, the counterparty for money entering the system.
CREATE TABLE wallets (
    id             UUID        PRIMARY KEY,
    user_id        TEXT        NOT NULL,
    balance_paise  BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT wallets_user_id_unique UNIQUE (user_id),
    CONSTRAINT wallets_no_overdraft CHECK (balance_paise >= 0 OR allow_negative)
);

-- Every money movement is a row here, whatever the kind. Idempotency is scoped to the caller.
CREATE TABLE transfers (
    id                  UUID        PRIMARY KEY,
    kind                TEXT        NOT NULL,
    initiated_by        TEXT        NOT NULL,
    idempotency_key     TEXT        NOT NULL,
    request_fingerprint TEXT        NOT NULL,
    from_wallet_id      UUID        NOT NULL REFERENCES wallets (id),
    to_wallet_id        UUID        NOT NULL REFERENCES wallets (id),
    amount_paise        BIGINT      NOT NULL,
    status              TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ,
    CONSTRAINT transfers_idempotency_unique UNIQUE (initiated_by, idempotency_key),
    CONSTRAINT transfers_kind_valid CHECK (kind IN ('TRANSFER', 'TOPUP')),
    CONSTRAINT transfers_status_valid CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED_INSUFFICIENT_FUNDS')),
    CONSTRAINT transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id)
);
CREATE INDEX idx_transfers_from_wallet ON transfers (from_wallet_id);
CREATE INDEX idx_transfers_to_wallet ON transfers (to_wallet_id);

-- Double-entry: the two legs of a settled transfer sum to zero, so conservation is auditable.
CREATE TABLE ledger_entries (
    id           BIGSERIAL   PRIMARY KEY,
    transfer_id  UUID        NOT NULL REFERENCES transfers (id),
    wallet_id    UUID        NOT NULL REFERENCES wallets (id),
    amount_paise BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_one_leg_per_wallet UNIQUE (transfer_id, wallet_id),
    CONSTRAINT ledger_entries_non_zero CHECK (amount_paise <> 0)
);
CREATE INDEX idx_ledger_entries_wallet ON ledger_entries (wallet_id);
