/**
 * Run the scheduled jobs once (for cron / manual use):  npm run jobs
 * Runs with the "react-server" condition so server-only modules load outside Next.
 */
import "dotenv/config";
import { runJobs } from "../src/lib/jobs";
import { db } from "../src/lib/db";

runJobs()
  .then((r) => console.log(JSON.stringify(r, null, 2)))
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(() => db.$disconnect());
