// Runs the backend's Maven Wrapper on any OS: `node scripts/mvnw.mjs <maven args>` (cwd: backend/).
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { loadEnv } from "./env.mjs";

loadEnv(); // the backend reads .env.<APP_ENV> itself too; this also sets SPRING_PROFILES_ACTIVE

const backend = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "backend");
const win = process.platform === "win32";
const env = { ...process.env };
// a stale JAVA_HOME (JDK since upgraded) breaks the wrapper; fall back to the java on PATH
if (env.JAVA_HOME && !existsSync(env.JAVA_HOME)) delete env.JAVA_HOME;
const cmd = win ? `"${path.join(backend, "mvnw.cmd")}"` : "./mvnw";
const r = spawnSync(cmd, process.argv.slice(2), { cwd: backend, stdio: "inherit", shell: win, env });
process.exit(r.status ?? 1);
