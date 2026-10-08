// The end-to-end test environment, separate from development data:
//   node scripts/e2e-env.mjs backend   → database "<db>_e2e" reset + demo seed, API on :8081
//   node scripts/e2e-env.mjs frontend  → Next.js built into .next-e2e (pointing at :8081), served on :3006
// Playwright starts both (playwright.config.ts). Uploaded files go to storage/e2e; no email is sent (no SMTP).
import "dotenv/config";
import { spawn, spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";

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
    DATABASE_URL: url.toString(),
    BACKEND_PORT: String(E2E.api),
    APP_URL: `http://localhost:${E2E.web}`,
    FRONTEND_ORIGIN: `http://localhost:${E2E.web}`,
    UPLOAD_DIR: "./storage/e2e",
    SEED_DEMO: "true",
    SMTP_HOST: "",
  };
  // the dev-only reset command empties the e2e database, migrates it and seeds the demo business
  run("java", ["-jar", copy, "reset"], { cwd: root, env });
  // run the scheduled jobs once (absences, the monthly lock, accrual) so the demo data is in its settled state
  run("java", ["-jar", copy, "jobs"], { cwd: root, env });
  const server = spawn("java", ["-jar", copy], { cwd: root, env, stdio: "inherit", shell: win });
  server.on("exit", (code) => process.exit(code ?? 0));
}

function frontend() {
  const dist = ".next-e2e";
  const env = { ...process.env, BACKEND_URL: `http://127.0.0.1:${E2E.api}`, NEXT_DIST_DIR: dist, APP_URL: `http://localhost:${E2E.web}` };
  // rebuild when any app source is newer than the last e2e build
  const newest = (dir) => readdirSync(dir, { withFileTypes: true }).reduce((m, e) => {
    const p = path.join(dir, e.name);
    return Math.max(m, e.isDirectory() ? newest(p) : statSync(p).mtimeMs);
  }, 0);
  const id = path.join(root, dist, "BUILD_ID");
  const sources = Math.max(newest(path.join(root, "src")), statSync(path.join(root, "next.config.ts")).mtimeMs);
  const built = existsSync(id) && statSync(id).mtimeMs > sources;
  if (!built || process.env.E2E_BUILD === "1") run("npx", ["next", "build"], { cwd: root, env });
  const web = spawn("npx", ["next", "start", "-p", String(E2E.web)], { cwd: root, env, stdio: "inherit", shell: win });
  web.on("exit", (code) => process.exit(code ?? 0));
}

const what = process.argv[2];
if (what === "backend") await backend();
else if (what === "frontend") frontend();
else {
  console.error("usage: node scripts/e2e-env.mjs backend|frontend");
  process.exit(2);
}
