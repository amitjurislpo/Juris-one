import { expect, test, type Page } from "@playwright/test";
import { DEMO_PW, emailOf, expectLanded, OWNER, sql } from "./helpers";

// The e2e backend uses the fixed sign-in code 000000 (OTP_FIXED_CODE in .env.dev, default in application.yml).
// Code requests are rate limited (10 per IP / 5 per email per 15 minutes): this file asks for at most 5 codes.
const CODE = "000000";
const BAD = "That code is wrong or has expired. Request a new one.";

async function openLogin(page: Page) {
  await page.goto("/login", { waitUntil: "networkidle" });
}

async function requestCode(page: Page, email: string) {
  await page.getByLabel("Email").fill(email);
  await page.getByRole("button", { name: "Send code" }).click();
  await expect(page.getByRole("heading", { name: "Enter your code" })).toBeVisible();
}

test.describe("email code sign-in", () => {
  test.afterAll(async () => {
    await sql(`UPDATE users SET failed_attempts = 0, locked_until = NULL`);
  });

  test("the form starts in email-code mode", async ({ page }) => {
    await openLogin(page);
    await expect(page.getByRole("button", { name: "Send code" })).toBeVisible();
    await expect(page.getByLabel("Password", { exact: true })).toHaveCount(0);
  });

  test("a wrong code is rejected, the right code lands on the role's page", async ({ page }) => {
    const email = await emailOf("JC-049");
    await openLogin(page);
    await requestCode(page, email);
    await expect(page.getByText(email)).toBeVisible();
    await page.getByLabel("Sign-in code").fill("123456");
    await page.getByRole("button", { name: "Verify and sign in" }).click();
    await expect(page.locator(".err")).toHaveText(BAD);
    await expect(page).toHaveURL(/\/login/);
    await page.getByLabel("Sign-in code").fill(CODE);
    await page.getByRole("button", { name: "Verify and sign in" }).click();
    await expectLanded(page, "/home");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Naman");
  });

  test("the owner lands on Overview with a code", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, await emailOf(OWNER));
    await page.getByLabel("Sign-in code").fill(CODE);
    await page.getByRole("button", { name: "Verify and sign in" }).click();
    await expectLanded(page, "/overview");
  });

  test("an unknown email gets the same answer, and no code ever works for it", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, "nobody.at.all@example.com"); // generic: same next step as for a real account
    await page.getByLabel("Sign-in code").fill(CODE);
    await page.getByRole("button", { name: "Verify and sign in" }).click();
    await expect(page.locator(".err")).toHaveText(BAD);
    await expect(page).toHaveURL(/\/login/);
  });

  test("an invalid email is refused before any code is sent", async ({ page }) => {
    await openLogin(page);
    await page.getByLabel("Email").fill("not-an-email");
    await page.getByRole("button", { name: "Send code" }).click();
    await expect(page.locator(".err")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Enter your code" })).toHaveCount(0);
  });

  test("'Use password instead' still signs in with a password, and the code form comes back", async ({ page }) => {
    await openLogin(page);
    await page.getByRole("button", { name: "Use password instead" }).click();
    await page.getByRole("button", { name: "Email me a code instead" }).click();
    await expect(page.getByRole("button", { name: "Send code" })).toBeVisible();
    await page.getByRole("button", { name: "Use password instead" }).click();
    await page.getByLabel("Email").fill(await emailOf("JC-072"));
    await page.getByLabel("Password", { exact: true }).fill(DEMO_PW);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expectLanded(page, "/home");
  });
});
