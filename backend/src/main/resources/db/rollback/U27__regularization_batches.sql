-- Reverses V27__regularization_batches.sql (run with psql, then delete version 27 from flyway_schema_history).
DROP INDEX IF EXISTS "regularization_requests_batch_id_idx";
ALTER TABLE "regularization_requests" DROP COLUMN IF EXISTS "batch_id";
