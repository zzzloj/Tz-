-- Site, account and moderation (stage 13).

-- Recovery code (bcrypt), shown to the player once: there is no e-mail to send a new password to.
ALTER TABLE accounts ADD COLUMN recovery_hash TEXT;
-- Ban (no entry) and mute (no chat, letters or forum posts) until this unix time; 0 — none.
ALTER TABLE accounts ADD COLUMN banned_until BIGINT NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN ban_reason   TEXT   NOT NULL DEFAULT '';
ALTER TABLE accounts ADD COLUMN muted_until  BIGINT NOT NULL DEFAULT 0;

-- «О себе»: shown when others look at the character.
ALTER TABLE characters ADD COLUMN about TEXT NOT NULL DEFAULT '';

-- What moderators and administrators did.
CREATE TABLE mod_log (
    id     BIGSERIAL PRIMARY KEY,
    at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor  TEXT        NOT NULL,
    action TEXT        NOT NULL,
    target TEXT        NOT NULL DEFAULT '',
    detail TEXT        NOT NULL DEFAULT ''
);

-- The forum (the old forum/: fsection, fthemes, fposts). staff_only: topics are started by moderators (news).
CREATE TABLE forum_sections (
    id         SERIAL  PRIMARY KEY,
    title      TEXT    NOT NULL,
    info       TEXT    NOT NULL DEFAULT '',
    position   INT     NOT NULL DEFAULT 0,
    staff_only BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE forum_topics (
    id         BIGSERIAL   PRIMARY KEY,
    section_id INT         NOT NULL REFERENCES forum_sections (id) ON DELETE CASCADE,
    title      TEXT        NOT NULL,
    author_id  BIGINT      REFERENCES accounts (id) ON DELETE SET NULL,
    author     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    bumped_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    pinned     BOOLEAN     NOT NULL DEFAULT false,
    closed     BOOLEAN     NOT NULL DEFAULT false
);
CREATE INDEX forum_topics_section ON forum_topics (section_id, pinned DESC, bumped_at DESC);

CREATE TABLE forum_posts (
    id         BIGSERIAL   PRIMARY KEY,
    topic_id   BIGINT      NOT NULL REFERENCES forum_topics (id) ON DELETE CASCADE,
    author_id  BIGINT      REFERENCES accounts (id) ON DELETE SET NULL,
    author     TEXT        NOT NULL,
    text       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_by  TEXT
);
CREATE INDEX forum_posts_topic ON forum_posts (topic_id, id);

INSERT INTO forum_sections (title, info, position, staff_only) VALUES
    ('Новости', 'Что нового в игре', 100, true),
    ('Общий', 'Разговоры обо всём, что касается Территории Зла', 90, false),
    ('Вопросы и помощь', 'Спросите тех, кто играет давно', 80, false),
    ('Кланы и замки', 'Набор в кланы, союзы и войны', 70, false),
    ('Торговля', 'Куплю, продам, обменяю', 60, false),
    ('Ошибки и предложения', 'Нашли ошибку — расскажите, где и как', 50, false);

-- The live world between restarts (WorldStore.kt): NPCs, pets, things on the ground, corpses, respawns.
CREATE TABLE world_snapshot (
    id       INT         PRIMARY KEY,
    data     TEXT        NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
