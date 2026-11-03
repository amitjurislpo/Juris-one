-- S27: a date-range regularization request is one request per day; the days of one range share a batch id.
ALTER TABLE "regularization_requests" ADD COLUMN "batch_id" UUID;
CREATE INDEX "regularization_requests_batch_id_idx" ON "regularization_requests"("batch_id") WHERE "batch_id" IS NOT NULL;
