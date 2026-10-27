CREATE TABLE rooms_new (
  id            TEXT PRIMARY KEY,
  token         TEXT NOT NULL UNIQUE,
  video_id      TEXT REFERENCES videos(id),
  created_at    TEXT NOT NULL DEFAULT (datetime('now')),
  allow_control INTEGER NOT NULL DEFAULT 0,
  title         TEXT
);
INSERT INTO rooms_new (id, token, video_id, created_at, allow_control, title)
  SELECT id, token, video_id, created_at, allow_control, title FROM rooms;
DROP TABLE rooms;
ALTER TABLE rooms_new RENAME TO rooms;
