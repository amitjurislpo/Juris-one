-- S39: half-day remote work. A remote work request is a range of full days (as before) or ONE day of which only the first or the second
-- half is remote; the other half is an ordinary working half (it needs half-day hours, like the other half of an On Duty half day).
-- Existing requests stay FULL. Nothing is dropped or rewritten.

ALTER TABLE "remote_work_requests" ADD COLUMN "part" TEXT NOT NULL DEFAULT 'FULL';
ALTER TABLE "remote_work_requests" ADD CONSTRAINT "remote_work_requests_part_check" CHECK ("part" IN ('FULL', 'FIRST_HALF', 'SECOND_HALF'));
-- a half day is a single day
ALTER TABLE "remote_work_requests" ADD CONSTRAINT "remote_work_requests_half_is_one_day_check" CHECK ("part" = 'FULL' OR "from_date" = "to_date");
