-- Character statistics (stage 10), the old `st` string: monsters, players killed, deaths, thefts,
-- fish, branches, ore, gems set, animals tamed, gems found — by index, docs/mechanics-progression.md §6.
ALTER TABLE characters ADD COLUMN stats JSONB NOT NULL DEFAULT '[]';
