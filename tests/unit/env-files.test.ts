import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { parse } from "dotenv";
import { describe, expect, it } from "vitest";
import { ENVIRONMENTS, resolveEnvName } from "../../scripts/env.mjs";

const root = path.resolve(import.meta.dirname, "../..");
const load = (name: string) => parse(readFileSync(path.join(root, `.env.${name}.example`)));
const all = Object.fromEntries(ENVIRONMENTS.map((e) => [e, load(e)]));

describe("environment files", () => {
  it("has the four environments", () => {
    expect(ENVIRONMENTS).toEqual(["dev", "sqe", "uat", "prd"]);
    for (const e of ENVIRONMENTS) expect(existsSync(path.join(root, `.env.${e}.example`)), e).toBe(true);
  });

  it("names itself and its compose project", () => {
    for (const e of ENVIRONMENTS) {
      expect(all[e].APP_ENV).toBe(e);
      expect(all[e].ENV_FILE).toBe(`.env.${e}`);
    }
  });

  it("defines the same keys in every environment", () => {
    const keys = Object.keys(all.dev).sort();
    for (const e of ENVIRONMENTS) expect(Object.keys(all[e]).sort(), e).toEqual(keys);
  });

  it("keeps ports, databases and compose projects separate", () => {
    for (const key of ["WEB_PORT", "BACKEND_PORT", "DB_PORT", "POSTGRES_DB", "COMPOSE_PROJECT_NAME", "UPLOAD_DIR"]) {
      const values = ENVIRONMENTS.map((e) => all[e][key]);
      expect(new Set(values).size, key).toBe(ENVIRONMENTS.length);
    }
  });

  it("points DATABASE_URL, BACKEND_URL and APP_URL at the environment's own ports and database", () => {
    for (const e of ENVIRONMENTS) {
      const c = all[e];
      const db = new URL(c.DATABASE_URL.replace(/^postgres(ql)?:/, "http:"));
      expect(db.port, e).toBe(c.DB_PORT);
      expect(db.pathname.slice(1), e).toBe(c.POSTGRES_DB);
      expect(c.BACKEND_URL, e).toContain(`:${c.BACKEND_PORT}`);
    }
    expect(all.dev.APP_URL).toContain(`:${all.dev.WEB_PORT}`);
    expect(all.sqe.APP_URL).toContain(`:${all.sqe.WEB_PORT}`);
  });

  it("allows the fixed sign-in code and demo data only in dev and sqe", () => {
    for (const e of ["dev", "sqe"]) expect(all[e].OTP_FIXED_CODE, e).toBe("000000");
    for (const e of ["uat", "prd"]) {
      expect(all[e].OTP_FIXED_CODE, e).toBe("");
      expect(all[e].SEED_DEMO, e).toBe("false");
      expect(all[e].APP_URL, e).toMatch(/^https:\/\//);
      expect(all[e].SMTP_HOST, e).not.toBe("");
    }
  });

  it("ships templates without secrets", () => {
    for (const e of ENVIRONMENTS) {
      for (const k of ["JWT_SECRET", "PII_ENCRYPTION_KEY", "POSTGRES_PASSWORD", "SUPER_ADMIN_PASSWORD"]) expect(all[e][k], `${e} ${k}`).toContain("<");
    }
  });
});

describe("resolveEnvName", () => {
  it("accepts the four names, case-insensitively, and defaults to dev", () => {
    expect(resolveEnvName("UAT")).toBe("uat");
    expect(resolveEnvName("prd")).toBe("prd");
    const saved = process.env.APP_ENV;
    delete process.env.APP_ENV;
    expect(resolveEnvName(undefined)).toBe("dev");
    if (saved !== undefined) process.env.APP_ENV = saved;
  });
});
