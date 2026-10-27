ALTER TABLE rooms ADD COLUMN title TEXT;
CREATE TABLE IF NOT EXISTS room_items (
  room_id  TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  video_id TEXT NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  PRIMARY KEY (room_id, video_id)
);
DELETE FROM rooms WHERE video_id NOT IN (SELECT id FROM videos);
INSERT INTO room_items (room_id, video_id, position)
  SELECT id, video_id, 0 FROM rooms;
