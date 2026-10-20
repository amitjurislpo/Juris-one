// Environment selection shared by the npm scripts. JurisOne has four environments; each one is the file
//   backend/src/main/resources/application-<env>.properties     (dev, sqe, uat, prd)
// on top of application.properties. The name comes from APP_ENV (default "dev"). The scripts read those same files for
// what docker-compose and the helper scripts need (ports, database, project name); the API reads them itself.
// ${PLACEHOLDERS} (the secrets of sqe/uat/prd) come from the real environment variables. Variables already present in
// the real environment always win.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const ENVIRONMENTS = ["dev", "sqe", "uat", "prd"];
export const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const resources = path.join(root, "backend", "src", "main", "resources");

export function resolveEnvName(name) {
  const env = (name || process.env.APP_ENV || "dev").toLowerCase();
  if (!ENVIRONMENTS.includes(env)) {
    console.error(`Unknown environment "${env}". Use one of: ${ENVIRONMENTS.join(", ")}.`);
    process.exit(1);
  }
  return env;
}

/** key=value lines of a .properties file (# comments). */
function parseProperties(file) {
  const out = {};
  for (const raw of readFileSync(file, "utf8").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#") || line.startsWith("!")) continue;
    const eq = line.indexOf("=");
    if (eq > 0) out[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return out;
}

/** application.properties + application-<env>.properties, with ${other.key:default} and ${ENV_VAR} placeholders filled in. */
export function readConfig(name) {
  const env = resolveEnvName(name);
  const props = { ...parseProperties(path.join(resources, "application.properties")), ...parseProperties(path.join(resources, `application-${env}.properties`)) };
  const fill = (value, depth = 0) =>
    depth > 5 ? value : value.replace(/\$\{([^}:]+)(?::([^}]*))?\}/g, (_, key, dflt) => {
      if (props[key] !== undefined) return fill(props[key], depth + 1);
      if (process.env[key] !== undefined) return process.env[key];
      return dflt ?? "";
    });
  return Object.fromEntries(Object.entries(props).map(([k, v]) => [k, fill(v)]));
}

/** jdbc:postgresql://host:port/db?currentSchema=x → the parts. */
function parseJdbc(url) {
  const m = /^jdbc:postgresql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/.exec(url ?? "");
  return m ? { host: m[1], port: m[2] ?? "5432", db: m[3] } : null;
}

/**
 * Selects the environment: sets APP_ENV and SPRING_PROFILES_ACTIVE, and (unless already set) what docker-compose and the
 * helper scripts use: COMPOSE_PROJECT_NAME, BACKEND_PORT, DB_PORT, POSTGRES_*, DATABASE_URL, APP_URL and the seed logins.
 * Returns the environment name.
 */
export function loadEnv(name) {
  const env = resolveEnvName(name);
  const cfg = readConfig(env);
  const db = parseJdbc(cfg["spring.datasource.url"]);
  const user = cfg["spring.datasource.username"];
  const password = cfg["spring.datasource.password"];
  const defaults = {
    APP_ENV: env,
    SPRING_PROFILES_ACTIVE: env,
    COMPOSE_PROJECT_NAME: env === "dev" ? "jurisone" : `jurisone-${env}`,
    BACKEND_PORT: cfg["server.port"],
    APP_URL: cfg["jurisone.app-url"],
    SUPER_ADMIN_EMAIL: cfg["jurisone.seed.super-admin-email"],
    SUPER_ADMIN_PASSWORD: cfg["jurisone.seed.super-admin-password"],
    SEED_OWNER_PASSWORD: cfg["jurisone.seed.owner-password"],
    SEED_DEMO_PASSWORD: cfg["jurisone.seed.demo-password"],
  };
  if (db) {
    Object.assign(defaults, {
      DB_PORT: db.port,
      POSTGRES_DB: db.db,
      POSTGRES_USER: user,
      POSTGRES_PASSWORD: password,
      DATABASE_URL: `postgresql://${encodeURIComponent(user ?? "")}:${encodeURIComponent(password ?? "")}@${db.host}:${db.port}/${db.db}?schema=public`,
    });
  }
  for (const [k, v] of Object.entries(defaults)) if (process.env[k] === undefined && v) process.env[k] = v;
  process.env.APP_ENV = env;
  process.env.SPRING_PROFILES_ACTIVE = process.env.SPRING_PROFILES_ACTIVE || env;
  return env;
}
