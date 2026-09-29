-- Bank cell of a character (stage 5): one per character, shared by all bankers of the world.
CREATE TABLE character_bank (
    character_id BIGINT  NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    item_id      TEXT    NOT NULL,
    count        INT     NOT NULL CHECK (count > 0),
    PRIMARY KEY (character_id, item_id)
);
