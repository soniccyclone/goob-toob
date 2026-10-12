CREATE TABLE IF NOT EXISTS videos (
  id            TEXT PRIMARY KEY,
  title         TEXT NOT NULL,
  original_path TEXT,
  dash_dir      TEXT,
  status        TEXT NOT NULL DEFAULT 'uploaded',
  progress      REAL NOT NULL DEFAULT 0,
  duration      REAL,
  ladder        TEXT,
  error         TEXT,
  created_at    TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS rooms (
  id         TEXT PRIMARY KEY,
  token      TEXT NOT NULL UNIQUE,
  video_id   TEXT NOT NULL REFERENCES videos(id),
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
