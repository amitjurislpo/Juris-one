import { runJobs } from "./jobs";

const EVERY_MS = 10 * 60_000;
const g = globalThis as unknown as { __joScheduler?: NodeJS.Timeout };

async function tick() {
  try {
    const r = await runJobs();
    if (!("skipped" in r)) {
      const locked = r.lock.locked.length ? ` · locked ${r.lock.locked.join(", ")}` : "";
      console.info(`[jobs] status ${r.status.changed} · days ${r.finalize.written}${locked}`);
    }
  } catch (e) {
    console.error("[jobs] failed", e);
  }
}

/** Every 10 minutes, plus once shortly after boot. Safe across hot reloads and multiple instances. */
export function startScheduler() {
  if (g.__joScheduler) return;
  g.__joScheduler = setInterval(tick, EVERY_MS);
  setTimeout(tick, 15_000);
}
