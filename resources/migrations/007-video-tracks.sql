-- #85: the probe's audio and subtitle tracks as JSON, {"audio" [...] "subs" [...]}.
-- NULL on rows encoded before this (one audio stream, no subtitles).
ALTER TABLE videos ADD COLUMN tracks TEXT;
