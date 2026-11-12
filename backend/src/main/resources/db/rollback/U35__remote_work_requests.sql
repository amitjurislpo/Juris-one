-- Reverses V35__remote_work_requests.sql (run with psql, then delete version 35 from flyway_schema_history).
-- Lost on purpose: every remote work request (pending, approved, rejected, withdrawn). Approved remote days become ordinary days again,
-- so a day without a punch is Absent (loss of pay) again once the rollback is done and attendance is recalculated.
DROP TABLE IF EXISTS "remote_work_requests";
