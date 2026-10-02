-- The world chronicle (stage 18): what happened in the world, written by the server,
-- shown on «Мир · События» for 7 days. clan_id set — seen only by that clan.
CREATE TABLE chronicle (
    id      BIGSERIAL PRIMARY KEY,
    at      BIGINT NOT NULL,
    text    TEXT   NOT NULL,
    clan_id BIGINT REFERENCES clans (id) ON DELETE CASCADE
);
CREATE INDEX chronicle_at ON chronicle (at);
