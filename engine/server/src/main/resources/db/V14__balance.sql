-- The balance of 04.10.2026 (claude/balance.md): levels from all experience ever
-- gained, attributes 1..10 starting at 2, 4 training points at creation.
-- The game is not open yet: every character starts over (things in the
-- backpack stay, nothing is worn — requirements changed).
ALTER TABLE characters ALTER COLUMN exp TYPE BIGINT;
ALTER TABLE characters ALTER COLUMN str SET DEFAULT 2;
ALTER TABLE characters ALTER COLUMN dex SET DEFAULT 2;
ALTER TABLE characters ALTER COLUMN intel SET DEFAULT 2;
ALTER TABLE characters ALTER COLUMN skill_points SET DEFAULT 4;
UPDATE characters SET str = 2, dex = 2, intel = 2, exp = 0, skill_points = 4, skills = '{}', hp = 25, mana = 20, ghost = FALSE;
UPDATE character_items SET equipped = FALSE;
-- Crafts grow by practice: the progress inside a step.
CREATE TABLE character_craft (
    character_id BIGINT NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
    craft        TEXT   NOT NULL,
    progress     INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, craft)
);
