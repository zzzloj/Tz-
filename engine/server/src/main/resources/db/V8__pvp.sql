-- PvP (stage 7): bounties placed at the officer (n.officer), the old wanted.dat.
CREATE TABLE bounties (
    character_id BIGINT PRIMARY KEY REFERENCES characters (id) ON DELETE CASCADE,
    amount       INT         NOT NULL CHECK (amount > 0),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
