/**
 * Starts the in-process job scheduler (absence marking, monthly lock, status changes) when the
 * Node.js server boots. Disable with JOBS_ENABLED=false (e.g. when running jobs from cron with
 * `npm run jobs`).
 */
export async function register() {
  if (process.env.NEXT_RUNTIME !== "nodejs" || process.env.JOBS_ENABLED === "false") return;
  const { startScheduler } = await import("./lib/scheduler");
  startScheduler();
}
