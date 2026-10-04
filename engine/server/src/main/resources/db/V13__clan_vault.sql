-- Clan vault (owner 04.10.2026): one per clan, opened at any banker or in the own castle's vault.
-- Each stack remembers who put it and the lowest rank allowed to take it.
CREATE TABLE clan_vault (
    id           BIGSERIAL PRIMARY KEY,
    clan_id      BIGINT NOT NULL REFERENCES clans (id) ON DELETE CASCADE,
    item_id      TEXT   NOT NULL,
    count        INT    NOT NULL CHECK (count > 0),
    access       TEXT   NOT NULL CHECK (access IN ('head', 'seneschal', 'vassal', 'neophyte')),
    owner_id     BIGINT NOT NULL,
    owner_name   TEXT   NOT NULL,
    UNIQUE (clan_id, item_id, access, owner_id)
);
CREATE INDEX clan_vault_clan ON clan_vault (clan_id);

CREATE TABLE clan_vault_log (
    id        BIGSERIAL PRIMARY KEY,
    clan_id   BIGINT      NOT NULL REFERENCES clans (id) ON DELETE CASCADE,
    at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    line      TEXT        NOT NULL
);
CREATE INDEX clan_vault_log_clan ON clan_vault_log (clan_id, id);
