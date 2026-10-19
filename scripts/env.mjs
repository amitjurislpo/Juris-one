// Environment selection shared by the npm scripts. JurisOne has four environments, each with its own file:
//   dev → .env.dev   sqe → .env.sqe   uat → .env.uat   prd → .env.prd
// The name comes from APP_ENV (default "dev"). Variables already present in the real environment always win.
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "dotenv";

export const ENVIRONMENTS = ["dev", "sqe", "uat", "prd"];
export const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

export function resolveEnvName(name) {
  const env = (name || process.env.APP_ENV || "dev").toLowerCase();
  if (!ENVIRONMENTS.includes(env)) {
    console.error(`Unknown environment "${env}". Use one of: ${ENVIRONMENTS.join(", ")}.`);
    process.exit(1);
  }
  return env;
}

/** Loads .env.<name> into process.env (existing variables are kept) and returns the environment name. */
export function loadEnv(name) {
  const env = resolveEnvName(name);
  const file = path.join(root, `.env.${env}`);
  if (!existsSync(file)) {
    console.error(`Missing ${path.basename(file)}. Copy ${path.basename(file)}.example to ${path.basename(file)} and fill it in.`);
    process.exit(1);
  }
  for (const [k, v] of Object.entries(parse(readFileSync(file)))) if (process.env[k] === undefined) process.env[k] = v;
  process.env.APP_ENV = env;
  process.env.SPRING_PROFILES_ACTIVE ??= env;
  return env;
}
