import { expect, test, type Page } from "@playwright/test";
import { expectLanded, login, OWNER, sql } from "./helpers";

const toast = (page: Page) => page.locator(".toast").last();
const T0 = new Date().toISOString();
const TAG = `E2E-LT-${Date.now() % 100000}`;
const TITLE = `E2E notice ${Date.now() % 100000}`;

async function cleanup() {
  await sql(`DELETE FROM asset_assignments WHERE asset_id IN (SELECT id FROM assets WHERE tag LIKE 'E2E-LT-%')`);
  await sql(`DELETE FROM assets WHERE tag LIKE 'E2E-LT-%'`);
  await sql(`DELETE FROM announcements WHERE title LIKE 'E2E notice %'`);
  await sql(`DELETE FROM notifications WHERE created_at >= $1`, [T0]);
}

test.describe("assets & announcements", () => {
  test.beforeAll(cleanup);
  test.afterAll(cleanup);

  test("HR adds an asset, assigns it, the employee sees it, HR takes it back", async ({ page, browser }) => {
    await login(page, "JC-012"); // HR manager
    await expectLanded(page, "/home");
    await page.goto("/assets");
    await page.getByRole("button", { name: "Add asset" }).click();
    const form = page.getByRole("dialog", { name: "Add asset" });
    await form.getByLabel(/Asset tag/).fill(TAG);
    await form.getByLabel("Make").fill("Dell");
    await form.getByLabel("Model").fill("Latitude E2E");
    await form.getByLabel("Serial number").fill("SN-E2E-1");
    await form.getByRole("button", { name: "Save" }).click();
    await expect(toast(page)).toBeVisible();
    const row = page.locator("tbody tr", { hasText: TAG });
    await expect(row.locator(".chip")).toHaveText("Available");

    // a duplicate tag is refused
    await page.getByRole("button", { name: "Add asset" }).click();
    const dup = page.getByRole("dialog", { name: "Add asset" });
    await dup.getByLabel(/Asset tag/).fill(TAG);
    await dup.getByRole("button", { name: "Save" }).click();
    await expect(dup.getByRole("alert")).toBeVisible();
    await dup.getByRole("button", { name: "Cancel" }).click();

    await row.getByRole("button", { name: "Assign" }).click();
    const assign = page.getByRole("dialog", { name: `Assign ${TAG}` });
    await assign.getByLabel(/Employee/).selectOption({ label: "Naman Tyagi (JC-049)" });
    await assign.getByLabel(/Notes/).fill("With charger");
    await assign.getByRole("button", { name: "Assign" }).click();
    await expect(row.locator(".chip")).toHaveText("Assigned");
    await expect(row).toContainText("Naman Tyagi");

    // the employee: My assets + a notification
    const ctx = await browser.newContext();
    const emp = await ctx.newPage();
    await login(emp, "JC-049");
    await expectLanded(emp, "/home");
    await emp.getByRole("complementary", { name: "Main menu" }).getByRole("link", { name: "My assets" }).click();
    await expectLanded(emp, "/my-assets");
    const mine = emp.locator("tbody tr", { hasText: TAG });
    await expect(mine).toContainText("Dell Latitude E2E");
    await expect(mine).toContainText("With charger");
    await emp.goto("/notifications");
    await expect(emp.getByText(new RegExp(TAG)).first()).toBeVisible();

    // return flow
    await row.getByRole("button", { name: "Take back" }).click();
    const back = page.getByRole("dialog", { name: `Take back ${TAG}` });
    await back.locator("select").selectOption("GOOD");
    await back.getByRole("button", { name: "Mark returned" }).click();
    await expect(row.locator(".chip")).toHaveText("Available");

    await emp.goto("/my-assets");
    await expect(emp.getByRole("heading", { name: "Returned" })).toBeVisible();
    await expect(emp.locator("tbody tr", { hasText: TAG })).toHaveCount(1);
    await expect(emp.getByText("Nothing assigned")).toBeVisible();
    await ctx.close();
  });

  test("owner posts an announcement; an employee sees it on Home and in notifications", async ({ page, browser }) => {
    await login(page, OWNER);
    await expectLanded(page, "/overview");
    await page.goto("/manage-announcements");
    await page.getByRole("button", { name: "New announcement" }).click();
    const ed = page.getByRole("dialog", { name: "New announcement" });
    await ed.getByLabel(/Title/).fill(TITLE);
    await ed.getByLabel(/Message/).fill("Office closed early on Friday for the E2E party.");
    await ed.getByLabel("Pin to the top").check();
    await ed.getByRole("button", { name: "Publish" }).click();
    const row = page.locator("tbody tr", { hasText: TITLE });
    await expect(row.locator(".chip", { hasText: "Showing" })).toBeVisible();

    const ctx = await browser.newContext();
    const emp = await ctx.newPage();
    await login(emp, "JC-049");
    await expectLanded(emp, "/home");
    await expect(emp.getByText(TITLE).first()).toBeVisible();
    await emp.goto("/notifications");
    await expect(emp.getByText(`Announcement: ${TITLE}`)).toBeVisible();
    await emp.goto("/announcements");
    await expect(emp.getByText(TITLE).first()).toBeVisible();

    // unpublish hides it
    await row.getByRole("button", { name: "Unpublish" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Unpublish" }).click();
    await expect(row.locator(".chip", { hasText: "Unpublished" })).toBeVisible();
    await emp.goto("/announcements");
    await expect(emp.getByText(TITLE)).toHaveCount(0);
    await ctx.close();
  });

  test("an ordinary employee cannot open the asset register or the announcement editor", async ({ page }) => {
    await login(page, "JC-049");
    await expectLanded(page, "/home");
    for (const path of ["/assets", "/manage-announcements"]) {
      await page.goto(path);
      await expectLanded(page, "/home");
    }
    const menu = page.getByRole("complementary", { name: "Main menu" });
    await expect(menu.getByRole("link", { name: "Assets", exact: true })).toHaveCount(0);
    expect((await page.request.get("/api/assets")).status()).toBe(403);
  });
});
