-- Social (stage 6): contacts, private and clan messages, clans.

-- Contacts: a character writes only to those who added it (the old anti-spam rule of f_msg.dat).
CREATE TABLE contacts (
    character_id BIGINT NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    contact_id   BIGINT NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    PRIMARY KEY (character_id, contact_id)
);

CREATE TABLE clans (
    id         BIGSERIAL PRIMARY KEY,
    world_id   INT         NOT NULL DEFAULT 1,
    name       TEXT        NOT NULL,
    info       TEXT        NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX clans_name ON clans (world_id, lower(name));

-- rank: head (сеньор), seneschal (сенешаль), vassal (вассал), neophyte (неофит).
CREATE TABLE clan_members (
    character_id BIGINT PRIMARY KEY REFERENCES characters (id) ON DELETE CASCADE,
    clan_id      BIGINT NOT NULL REFERENCES clans (id) ON DELETE CASCADE,
    rank         TEXT   NOT NULL CHECK (rank IN ('head', 'seneschal', 'vassal', 'neophyte'))
);
CREATE INDEX clan_members_clan ON clan_members (clan_id);

CREATE TABLE clan_invites (
    clan_id      BIGINT NOT NULL REFERENCES clans (id) ON DELETE CASCADE,
    character_id BIGINT NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    PRIMARY KEY (clan_id, character_id)
);

-- Private messages and clan messages (one row per recipient). Kept 30 days, 100 per recipient.
CREATE TABLE messages (
    id         BIGSERIAL PRIMARY KEY,
    to_id      BIGINT      NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    from_name  TEXT        NOT NULL,
    clan       BOOLEAN     NOT NULL DEFAULT false,
    text       TEXT        NOT NULL,
    read       BOOLEAN     NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX messages_to ON messages (to_id, id DESC);
