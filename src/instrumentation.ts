/**
 * The scheduled jobs (absence marking, monthly lock, status changes, leave accrual) now run in the Spring Boot
 * backend (backend/…/jobs, JOBS_ENABLED). Nothing to start in the Next.js server.
 */
export async function register() {}
