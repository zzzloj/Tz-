-- Combat (stage 3): a dead character walks as a ghost until resurrected.
ALTER TABLE characters ADD COLUMN ghost BOOLEAN NOT NULL DEFAULT false;
