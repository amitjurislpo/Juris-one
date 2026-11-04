-- Reverses V33__punch_note.sql (run with psql, then delete version 33 from flyway_schema_history).
-- The check-out notes are lost; nothing else depends on them.
ALTER TABLE "punches" DROP CONSTRAINT "punches_note_length";
ALTER TABLE "punches" DROP COLUMN "note";
