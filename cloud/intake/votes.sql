CREATE TABLE IF NOT EXISTS name_votes (game TEXT NOT NULL, en TEXT NOT NULL, voter TEXT NOT NULL, PRIMARY KEY (game, en, voter));
