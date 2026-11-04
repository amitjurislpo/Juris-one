-- An optional note the employee writes when checking out ("what I worked on"), shown to the employee and to approvers.
-- Only check-out punches carry one; the application validates, strips control characters and stores NULL when blank.
ALTER TABLE "punches" ADD COLUMN "note" TEXT;
ALTER TABLE "punches" ADD CONSTRAINT "punches_note_length" CHECK ("note" IS NULL OR char_length("note") <= 500);
