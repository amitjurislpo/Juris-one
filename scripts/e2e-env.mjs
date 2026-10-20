// The end-to-end test environment, separate from development data:
//   node scripts/e2e-env.mjs backend   → database "<db>_e2e" reset + demo seed, API on :8081
// The frontend repo (juris-one-frontend) starts this for its Playwright tests and serves the SPA on :3006 itself.
// Uploaded files go to storage/e2e; no email is sent (no SMTP).
import { spawn, spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";
import { loadEnv } from "./env.mjs";

loadEnv(); // the dev environment (application-dev.properties) unless APP_ENV says otherwise; the e2e database is "<db>_e2e"

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const win = process.platform === "win32";
export const E2E = { api: 8081, web: 3006 };

/** postgresql://u:p@host:port/db?x → same server, database "db_e2e". */
function e2eDatabaseUrl() {
  const url = new URL(process.env.DATABASE_URL);
  url.pathname = `${url.pathname.replace(/^\//, "")}_e2e`.replace(/^/, "/");
  return url;
}

async function ensureDatabase(url) {
  const admin = new URL(url);
  admin.pathname = new URL(process.env.DATABASE_URL).pathname;
  admin.search = "";
  const c = new pg.Client({ connectionString: admin.toString() });
  await c.connect();
  try {
    const name = url.pathname.slice(1);
    const r = await c.query("SELECT 1 FROM pg_database WHERE datname = $1", [name]);
    if (!r.rowCount) await c.query(`CREATE DATABASE "${name.replace(/"/g, "")}"`);
  } finally {
    await c.end();
  }
}

function run(cmd, args, opts) {
  const r = spawnSync(cmd, args, { stdio: "inherit", shell: win, ...opts });
  if (r.status !== 0) process.exit(r.status ?? 1);
}

async function backend() {
  const url = e2eDatabaseUrl();
  await ensureDatabase(url);
  const jar = path.join(root, "backend", "target", "jurisone-backend-0.1.0.jar");
  if (!existsSync(jar) || process.env.E2E_BUILD === "1") run("node", [path.join(root, "scripts", "mvnw.mjs"), "-q", "-DskipTests", "package"], { cwd: root });
  // run a copy: rebuilding the backend while the tests run must not touch the jar in use
  const dir = path.join(root, "storage", "e2e");
  mkdirSync(dir, { recursive: true });
  const copy = path.join(dir, "backend.jar");
  copyFileSync(jar, copy);
  const env = {
    ...process.env,
    SPRING_PROFILES_ACTIVE: "dev",
    SPRING_DATASOURCE_URL: `jdbc:postgresql://${url.host}${url.pathname}?currentSchema=public`,
    SPRING_DATASOURCE_USERNAME: decodeURIComponent(url.username),
    SPRING_DATASOURCE_PASSWORD: decodeURIComponent(url.password),
    SERVER_PORT: String(E2E.api),
    JURISONE_APP_URL: `http://localhost:${E2E.web}`,
    JURISONE_FRONTEND_ORIGIN: `http://localhost:${E2E.web}`,
    JURISONE_UPLOAD_DIR: "./storage/e2e",
    JURISONE_SEED_DEMO: "true",
    JURISONE_MAIL_HOST: "",
    // sign-in is by emailed code; in the e2e environment every code is 000000 and requests are not throttled
    JURISONE_OTP_FIXED_CODE: "000000",
    JURISONE_OTP_REQUESTS_PER_IP: "100000",
    JURISONE_OTP_REQUESTS_PER_EMAIL: "100000",
  };
  // the dev-only reset command empties the e2e database, migrates it and seeds the demo business
  run("java", ["-jar", copy, "reset"], { cwd: root, env });
  // run the scheduled jobs once (absences, the monthly lock, accrual) so the demo data is in its settled state
  run("java", ["-jar", copy, "jobs"], { cwd: root, env });
  const server = spawn("java", ["-jar", copy], { cwd: root, env, stdio: "inherit", shell: win });
  server.on("exit", (code) => process.exit(code ?? 0));
}

const what = process.argv[2];
if (what === "backend") await backend();
else {
  console.error("usage: node scripts/e2e-env.mjs backend");
  process.exit(2);
}
