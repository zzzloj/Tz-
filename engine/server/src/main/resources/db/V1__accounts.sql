-- Accounts, sessions and characters (stage 1).
CREATE TABLE accounts (
    id            BIGSERIAL PRIMARY KEY,
    login         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL DEFAULT 'player',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX accounts_login ON accounts (lower(login));

-- Only a SHA-256 of the token is stored: a database leak does not leak sessions.
CREATE TABLE sessions (
    token_hash BYTEA       PRIMARY KEY,
    account_id BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_account ON sessions (account_id);

-- world_id: one world for now, but every character belongs to a world (README "Миры").
CREATE TABLE characters (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    world_id     INT         NOT NULL DEFAULT 1,
    name         TEXT        NOT NULL,
    sex          CHAR(1)     NOT NULL CHECK (sex IN ('m', 'f')),
    location     TEXT        NOT NULL,
    str          INT         NOT NULL DEFAULT 1,
    dex          INT         NOT NULL DEFAULT 1,
    intel        INT         NOT NULL DEFAULT 1,
    hp           INT         NOT NULL,
    mana         INT         NOT NULL,
    exp          INT         NOT NULL DEFAULT 0,
    skill_points INT         NOT NULL DEFAULT 2,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX characters_name ON characters (world_id, lower(name));
CREATE UNIQUE INDEX characters_account_world ON characters (account_id, world_id);
