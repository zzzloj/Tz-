-- Inventory (stage 2). One row per item id; instance ids keep their suffixes
-- (maker, sharpening, gems) as in the old engine, so they stack only when equal.
CREATE TABLE character_items (
    character_id BIGINT  NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    item_id      TEXT    NOT NULL,
    count        INT     NOT NULL CHECK (count > 0),
    equipped     BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (character_id, item_id)
);

-- Characters created before stage 2 get the starting knife too.
INSERT INTO character_items (character_id, item_id, count)
SELECT id, 'i.w.k.begin', 1 FROM characters
ON CONFLICT DO NOTHING;
