// Runs a command with one environment selected (settings from application-<env>.properties):  node scripts/with-env.mjs <dev|sqe|uat|prd> <command...>
//   node scripts/with-env.mjs sqe npm run db:migrate      node scripts/with-env.mjs uat npm run backend:uat
// Use "-" instead of the name to take it from APP_ENV (default dev).
import { spawnSync } from "node:child_process";
import { loadEnv } from "./env.mjs";

const [first, ...cmd] = process.argv.slice(2);
if (!first || !cmd.length) {
  console.error("Usage: node scripts/with-env.mjs <dev|sqe|uat|prd|-> <command...>");
  process.exit(1);
}
const env = loadEnv(first === "-" ? undefined : first);
console.log(`[env] ${env}`);
const r = spawnSync(cmd.join(" "), { stdio: "inherit", shell: true, env: process.env });
process.exit(r.status ?? 1);
