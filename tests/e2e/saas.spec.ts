import { expect, test, type Browser, type Page } from "@playwright/test";
import { ADMIN_EMAIL, ADMIN_PW, expectLanded, login, sql } from "./helpers";

/**
 * The multi-business journey: the super admin creates a business and its sub admin, grants Juris Track; the sub
 * admin publishes a policy and onboards a new hire by link; the hire accepts the policy before anything else; and a
 * cancelled subscription pauses the business (sign-in and the employee export still work).
 */
const run = Math.random().toString(36).slice(2, 8);
const BUSINESS = `E2E Advisors ${run}`;
const OWNER_EMAIL = `owner.${run}@e2e-advisors.test`;
const OWNER_PW = `Owner-pass-${run}9`;
const CANDIDATE = `cand.${run}@gmail.test`;
const WORK_EMAIL = `riya.${run}@e2e-advisors.test`;
const HIRE_PW = `Hire-pass-${run}7`;
const PDF = { name: "doc.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4\n%e2e\n") };

const toast = (page: Page) => page.locator(".toast").last();

async function lastLink(to: string, kind: "onboard" | "invite") {
  await expect.poll(async () => (await sql("SELECT 1 FROM email_outbox WHERE \"to\" = $1", [to])).length, { timeout: 10_000 }).toBeGreaterThan(0);
  const [m] = await sql<{ text: string }>("SELECT text FROM email_outbox WHERE \"to\" = $1 ORDER BY created_at DESC LIMIT 1", [to]);
  const link = new RegExp(`/${kind}/[A-Za-z0-9_-]+`).exec(m.text);
  expect(link).not.toBeNull();
  return link![0];
}

async function asOwner(page: Page) {
  await login(page, OWNER_EMAIL, OWNER_PW);
  await expectLanded(page, "/overview");
}

test.describe.serial("a business on JurisOne, end to end", () => {
  let businessPath = "";

  test("the super admin creates a business with a plan and its sub admin, and grants Juris Track", async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PW);
    await expectLanded(page, "/platform");
    await page.getByRole("button", { name: "New business" }).click();
    const d = page.getByRole("dialog", { name: "New business" });
    await d.getByLabel("Business name").fill(BUSINESS);
    await d.getByLabel("Employee ID prefix").fill("EA");
    const [plan] = await sql<{ id: string }>("SELECT id::text FROM subscription_plans WHERE name = 'Standard'");
    await d.getByRole("combobox", { name: /^Plan/ }).selectOption(plan.id);
    await d.getByLabel("Name *", { exact: true }).fill("Esha Owner");
    await d.getByLabel(/Email \(signs in with it\)/).fill(OWNER_EMAIL);
    await d.getByLabel(/^Password/).fill(OWNER_PW);
    await d.getByRole("button", { name: "Create business" }).click();
    await expect(page).toHaveURL(/\/platform\/[0-9a-f-]{36}$/);
    businessPath = new URL(page.url()).pathname;
    await expect(page.getByRole("heading", { name: BUSINESS })).toBeVisible();
    await expect(page.getByText("Esha Owner")).toBeVisible();
    await expect(page.getByText("Not in the plan. Grant it on its own")).toBeVisible();

    await page.getByRole("button", { name: "Grant Juris Track" }).click();
    const g = page.getByRole("dialog", { name: "Grant Juris Track" });
    await g.getByLabel("Notes").fill("Launch partner");
    await g.getByRole("button", { name: "Grant", exact: true }).click();
    await expect(toast(page)).toContainText("Juris Track granted");
    await expect(page.locator("tr", { hasText: "Launch partner" }).locator(".chip")).toHaveText("Active");
  });

  test("the sub admin runs the business: Juris Track, subscription and a policy everyone must accept", async ({ page }) => {
    await asOwner(page);
    await expect(page.locator(".side")).toContainText(BUSINESS);
    await page.getByRole("link", { name: "Juris Track" }).click();
    await expect(page.getByText("Coming soon")).toBeVisible();
    await page.goto("/subscription");
    await expect(page.getByText("Standard", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Export employees (Excel)" })).toBeVisible();

    await page.goto("/policies");
    await page.getByRole("button", { name: "New policy" }).click();
    await page.getByRole("dialog", { name: "New policy" }).getByLabel("Title").fill("Code of conduct");
    await page.getByRole("button", { name: "Create draft" }).click();
    await expect(page).toHaveURL(/\/policies\/[0-9a-f-]{36}$/);
    await page.getByLabel(/Policy text/).fill("Be kind.\n\nBe on time.");
    await page.getByRole("button", { name: "Save draft" }).click();
    await expect(toast(page)).toContainText("Draft saved");
    await page.getByRole("button", { name: "Publish", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Publish" }).click();
    await expect(toast(page)).toContainText("Version 1 published");
  });

  test("the sub admin onboards a new hire by link; the candidate fills in details and documents", async ({ page, browser }) => {
    await asOwner(page);
    await page.goto("/onboarding");
    await page.getByRole("button", { name: "New onboarding" }).click();
    const d = page.getByRole("dialog", { name: "New onboarding" });
    await d.getByLabel(/Candidate name/).fill("Riya Sen");
    await d.getByLabel(/Personal email/).fill(CANDIDATE);
    await d.getByLabel("Designation").fill("Associate");
    await d.getByRole("button", { name: "Send link" }).click();
    await expect(page).toHaveURL(/\/onboarding\/[0-9a-f-]{36}$/);
    await expect(page.locator(".chip", { hasText: "Link sent" })).toBeVisible();

    await candidateFillsIn(browser, await lastLink(CANDIDATE, "onboard"));

    // HR verifies every document, marks the onboarding verified and adds the employee
    await page.reload();
    await expect(page.locator(".chip", { hasText: "To review" }).first()).toBeVisible();
    for (let i = 0; i < 7; i++) {
      await page.getByRole("button", { name: "Verify" }).first().click();
      await expect(toast(page)).toContainText("Document verified");
    }
    await page.getByRole("button", { name: "Mark verified" }).click();
    await expect(toast(page)).toContainText("Onboarding verified");
    await page.getByRole("button", { name: "Add employee" }).click();
    const a = page.getByRole("dialog", { name: "Add employee" });
    await a.getByLabel(/Work email/).fill(WORK_EMAIL);
    await a.getByLabel(/Department/).selectOption({ label: "Human Resources" });
    await a.getByLabel(/Role/).selectOption({ label: "Employee" });
    await a.getByLabel(/Reporting manager/).selectOption({ index: 1 });
    await a.getByLabel(/Joining date/).fill("2026-10-01");
    await a.getByRole("button", { name: "Add employee" }).click();
    await expect(toast(page)).toContainText("portal access is off until you turn it on");
    await expect(page.locator(".chip", { hasText: "Employee added" })).toBeVisible();
  });

  test("access on → invite to the work email → the new hire must accept the policy first", async ({ page, browser }) => {
    await asOwner(page);
    await page.goto("/employees");
    await page.getByRole("switch", { name: "Enable portal access for Riya Sen" }).click();
    await expect(toast(page)).toContainText("invite sent to their work email");

    const ctx = await browser.newContext();
    const hire = await ctx.newPage();
    await hire.goto(await lastLink(WORK_EMAIL, "invite"));
    await hire.getByLabel(/New password/).fill(HIRE_PW);
    await hire.getByLabel("Confirm password").fill(HIRE_PW);
    await hire.getByRole("button", { name: "Activate account" }).click();
    await expect(hire).toHaveURL(/\/login/);
    await login(hire, WORK_EMAIL, HIRE_PW);
    await expect(hire).toHaveURL(/\/acknowledge$/, { timeout: 15_000 });
    const popup = hire.getByRole("dialog", { name: "Code of conduct" });
    await expect(popup.getByText("Be on time.")).toBeVisible();
    await hire.goto("/leave"); // no way around it
    await expect(hire).toHaveURL(/\/acknowledge$/);
    await popup.getByRole("checkbox").check();
    await popup.getByRole("button", { name: "I accept" }).click();
    await expect(hire).toHaveURL(/\/home$/, { timeout: 15_000 });
    // the acceptance is on record
    const [ack] = await sql<{ employee_email: string; version: number }>(
      "SELECT employee_email, version FROM policy_acknowledgements WHERE employee_email = $1", [WORK_EMAIL]);
    expect(ack.version).toBe(1);
    // My profile: personal details from onboarding, email locked
    await hire.goto("/profile");
    await expect(hire.getByText(WORK_EMAIL)).toBeVisible();
    await expect(hire.getByLabel("Emergency contact", { exact: true })).toHaveValue("Amit Sen");
    await ctx.close();

    // HR sees who accepted
    await page.goto("/policies");
    await expect(page.locator("tr", { hasText: "Code of conduct" })).toContainText("1 / 1");
  });

  test("a cancelled subscription pauses the business; sign-in and the export still work", async ({ page, browser }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PW);
    await expectLanded(page, "/platform");
    await page.goto(businessPath);
    await page.getByRole("button", { name: /^Cancel Standard/ }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Cancel subscription" }).click();
    await expect(toast(page)).toContainText("Subscription cancelled");

    const ctx = await browser.newContext();
    const owner = await ctx.newPage();
    await login(owner, OWNER_EMAIL, OWNER_PW);
    await expect(owner).toHaveURL(/\/paused$/, { timeout: 15_000 });
    await expect(owner.getByRole("heading", { name: "JurisOne is paused" })).toBeVisible();
    const download = owner.waitForEvent("download");
    await owner.getByRole("link", { name: "Export employees (Excel)" }).click();
    expect((await download).suggestedFilename()).toMatch(/\.xlsx$/);
    expect((await owner.request.get("/api/people")).status()).toBe(402);
    await ctx.close();
  });

});

async function candidateFillsIn(browser: Browser, link: string) {
  const ctx = await browser.newContext(); // signed out: the link is the only credential
  const c = await ctx.newPage();
  await c.goto(link);
  await expect(c.getByRole("heading", { name: /Welcome, Riya/ })).toBeVisible();
  await c.getByLabel("Father's name *").fill("Amit Sen");
  await c.getByLabel("Date of birth *").fill("1996-04-02");
  await c.getByLabel("Mobile number", { exact: true }).fill("9810011223");
  await c.getByLabel("Current address *").fill("14 Park Street, Kolkata 700016");
  await c.getByLabel("Bank *").fill("HDFC Bank");
  await c.getByLabel("Account number *").fill("50100123456789");
  await c.getByLabel("IFSC *").fill("HDFC0001234");
  await c.getByLabel("Emergency contact *").fill("Amit Sen");
  await c.getByLabel("Emergency contact phone", { exact: true }).fill("9810044556");
  await c.getByRole("button", { name: "Save for later" }).click();
  await expect(c.getByText("Saved. You can come back")).toBeVisible();
  const kinds = ["ID_PROOF", "ADDRESS_PROOF", "PAN", "AADHAAR", "SALARY_SLIP", "SALARY_SLIP", "SALARY_SLIP"];
  for (const [i, kind] of kinds.entries()) {
    await c.getByLabel("Document type").selectOption(kind);
    await c.locator('input[type="file"]').setInputFiles(PDF);
    await expect(c.locator("table.t tbody tr")).toHaveCount(i + 1); // wait for this upload before the next
  }
  await c.getByRole("button", { name: "Submit to HR" }).click();
  await expect(c.getByText("Thank you — your details were sent to HR")).toBeVisible();
  await expect(c.getByText("Submitted.")).toBeVisible();
  await ctx.close();
}
