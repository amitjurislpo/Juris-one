// Runs a command with one environment's variables:  node scripts/with-env.mjs <dev|sqe|uat|prd> <command...>
//   node scripts/with-env.mjs uat next start      node scripts/with-env.mjs sqe npm run db:migrate
// Use "-" instead of the name to take it from APP_ENV (default dev). WEB_PORT (if set) becomes the app's PORT.
import { spawnSync } from "node:child_process";
import { loadEnv } from "./env.mjs";

const [first, ...cmd] = process.argv.slice(2);
if (!first || !cmd.length) {
  console.error("Usage: node scripts/with-env.mjs <dev|sqe|uat|prd|-> <command...>");
  process.exit(1);
}
const env = loadEnv(first === "-" ? undefined : first);
if (process.env.WEB_PORT) process.env.PORT = process.env.WEB_PORT;
console.log(`[env] ${env}`);
const r = spawnSync(cmd.join(" "), { stdio: "inherit", shell: true, env: process.env });
process.exit(r.status ?? 1);
