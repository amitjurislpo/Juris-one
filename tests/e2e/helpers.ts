import "dotenv/config";
import { expect, type Page } from "@playwright/test";
import { Client } from "pg";

export const DEMO_PW = process.env.SEED_DEMO_PASSWORD || "Juris@2026";
export const ADMIN_EMAIL = process.env.SUPER_ADMIN_EMAIL || "admin@jurislpo.com";
export const ADMIN_PW = process.env.SUPER_ADMIN_PASSWORD || "admin@jurislpo";

export async function login(page: Page, id: string, pw = DEMO_PW) {
  // wait for hydration — on a cold dev server a click can otherwise beat React to the form
  await page.goto("/login", { waitUntil: "networkidle" });
  await page.getByLabel("Employee ID or email").fill(id);
  await page.getByLabel("Password", { exact: true }).fill(pw);
  await page.getByRole("button", { name: "Sign in" }).click();
}

export async function expectLanded(page: Page, path: string) {
  await expect(page).toHaveURL(new RegExp(`${path}$`));
}

/** Direct DB access for test setup/teardown (unlock accounts, flip access). */
export async function sql<T = unknown>(q: string, params: unknown[] = []): Promise<T[]> {
  const c = new Client({ connectionString: process.env.DATABASE_URL!.replace("?schema=public", "") });
  await c.connect();
  try {
    return (await c.query(q, params)).rows as T[];
  } finally {
    await c.end();
  }
}
