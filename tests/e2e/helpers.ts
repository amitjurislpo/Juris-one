import "dotenv/config";
import { expect, type Page } from "@playwright/test";
import { Client } from "pg";

/** Passwords of the demo business (scripts/e2e-env.mjs seeds it into the e2e database). */
export const DEMO_PW = process.env.SEED_DEMO_PASSWORD || "";
/** JC-002 owns the demo business (its sub admin): the business admin. */
export const OWNER = "JC-002";
export const OWNER_PW = process.env.SEED_OWNER_PASSWORD || "";
/** The platform super admin (no business). */
export const ADMIN_EMAIL = process.env.SUPER_ADMIN_EMAIL || "";
export const ADMIN_PW = process.env.SUPER_ADMIN_PASSWORD || "";

/** The e2e database (never the development one): same server, database "<db>_e2e". */
export function e2eDatabaseUrl() {
  const url = new URL(process.env.DATABASE_URL!);
  url.pathname = `/${url.pathname.replace(/^\//, "")}_e2e`;
  url.search = "";
  return url.toString();
}

/** Direct DB access for test setup/teardown (unlock accounts, flip access, read the email outbox). */
export async function sql<T = unknown>(q: string, params: unknown[] = []): Promise<T[]> {
  const c = new Client({ connectionString: e2eDatabaseUrl() });
  await c.connect();
  try {
    return (await c.query(q, params)).rows as T[];
  } finally {
    await c.end();
  }
}

/** Sign-in is by email: a demo employee code (e.g. JC-072) is looked up. */
export async function emailOf(codeOrEmail: string) {
  if (codeOrEmail.includes("@")) return codeOrEmail;
  const [r] = await sql<{ email: string }>("SELECT email FROM employees WHERE upper(code) = upper($1) AND business_id IS NOT NULL", [codeOrEmail]);
  if (!r) throw new Error(`No employee ${codeOrEmail}`);
  return r.email;
}

export async function login(page: Page, id: string, pw?: string) {
  const email = await emailOf(id);
  const password = pw ?? (id.toUpperCase() === OWNER ? OWNER_PW : DEMO_PW);
  // wait for hydration — a click can otherwise beat React to the form
  await page.goto("/login", { waitUntil: "networkidle" });
  // the form starts in email-code mode: switch to the password form first
  await page.getByRole("button", { name: "Use password instead" }).click();
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

export async function expectLanded(page: Page, path: string) {
  await expect(page).toHaveURL(new RegExp(`${path}$`), { timeout: 15_000 });
}
