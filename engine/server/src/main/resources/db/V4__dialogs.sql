-- Dialogs, teachers and quests (stage 4).

-- Skills other than the attributes (str, dex, intel columns): {"coldweapon": 2, ...}.
ALTER TABLE characters ADD COLUMN skills JSONB NOT NULL DEFAULT '{}';

-- Spells (m.*) and techniques (p.*) learnt from teachers.
CREATE TABLE character_known (
    character_id BIGINT NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    id           TEXT   NOT NULL,
    PRIMARY KEY (character_id, id)
);

-- Quest state of a character: flags and personal timers. until = unix time, NULL = forever.
CREATE TABLE character_state (
    character_id BIGINT NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    key          TEXT   NOT NULL,
    value        TEXT   NOT NULL DEFAULT '',
    until        BIGINT,
    PRIMARY KEY (character_id, key)
);

-- State shared by the whole world: timers of big quests ("the baron takes no recruits for 5 hours").
CREATE TABLE world_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL DEFAULT '',
    until BIGINT
);
