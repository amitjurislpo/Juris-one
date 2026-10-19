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

/** In the e2e environment every emailed sign-in code is this fixed value (OTP_FIXED_CODE, see scripts/e2e-env.mjs). */
export const OTP = "000000";

/** Asks for a sign-in code for this employee and types it. Filling the first box with all six digits submits the form. */
export async function loginWithCode(page: Page, id: string, code: string) {
  const email = await emailOf(id);
  // wait for hydration — a click can otherwise beat React to the form
  await page.goto("/login", { waitUntil: "networkidle" });
  await page.getByLabel("Email").fill(email);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel("Digit 1 of 6").fill(code);
}

/** Signs in with the emailed code (sign-in is by code only). */
export async function login(page: Page, id: string) {
  await loginWithCode(page, id, OTP);
}

export async function expectLanded(page: Page, path: string) {
  await expect(page).toHaveURL(new RegExp(`${path}$`), { timeout: 15_000 });
}
