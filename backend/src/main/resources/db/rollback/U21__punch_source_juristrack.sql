-- Reverses V21__punch_source_juristrack.sql (run with psql, then delete version 21 from flyway_schema_history).
-- PostgreSQL cannot drop a value from an enum, so the punches are moved back to WEB and the type is rebuilt without it.
ALTER TABLE "punches" ALTER COLUMN "source" DROP DEFAULT;
ALTER TABLE "punches" ALTER COLUMN "source" TYPE TEXT USING (CASE WHEN "source"::text = 'JURISTRACK' THEN 'WEB' ELSE "source"::text END);
DROP TYPE "PunchSource";
CREATE TYPE "PunchSource" AS ENUM ('WEB', 'MOBILE', 'REGULARIZATION');
ALTER TABLE "punches" ALTER COLUMN "source" TYPE "PunchSource" USING "source"::"PunchSource";
ALTER TABLE "punches" ALTER COLUMN "source" SET DEFAULT 'WEB';
