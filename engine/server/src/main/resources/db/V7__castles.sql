-- Castles (stage 6): five clan castles c.1..c.5. In the old game their state
-- was packed into the name of the gate location; here it has columns.
CREATE TABLE castles (
    id           INT PRIMARY KEY,
    clan_id      BIGINT REFERENCES clans (id) ON DELETE SET NULL,
    sign         TEXT   NOT NULL DEFAULT '',
    -- Gate locked until (unix time); 0 = not locked.
    locked_until BIGINT NOT NULL DEFAULT 0,
    -- After opening (or a lock running out) the gate cannot be locked again until this time.
    open_until   BIGINT NOT NULL DEFAULT 0,
    knock_at     BIGINT NOT NULL DEFAULT 0
);
INSERT INTO castles (id) VALUES (1), (2), (3), (4), (5);

CREATE TABLE castle_guests (
    castle_id    INT    NOT NULL REFERENCES castles (id) ON DELETE CASCADE,
    character_id BIGINT NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    PRIMARY KEY (castle_id, character_id)
);

-- Hired castle guards (n.o.castle1..4): they serve whoever owns the castle until the contract ends.
CREATE TABLE castle_guards (
    key       TEXT   PRIMARY KEY,
    castle_id INT    NOT NULL REFERENCES castles (id) ON DELETE CASCADE,
    template  TEXT   NOT NULL,
    location  TEXT   NOT NULL,
    until     BIGINT NOT NULL
);
