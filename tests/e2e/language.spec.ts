import { expect, test } from "@playwright/test";
import { expectLanded, login } from "./helpers";

test.describe("language", () => {
  test("login page: switch to Hindi, persists across reload, switch back to English", async ({ page }) => {
    await page.goto("/login", { waitUntil: "networkidle" });
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await page.getByLabel("Language").selectOption({ label: "हिन्दी" });
    await expect(page.getByRole("heading", { name: "वापसी पर स्वागत है" })).toBeVisible();
    await expect(page.getByRole("button", { name: "कोड भेजें" })).toBeVisible();
    await page.reload();
    await expect(page.getByRole("heading", { name: "वापसी पर स्वागत है" })).toBeVisible();
    await page.getByLabel("भाषा").selectOption({ label: "English" });
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await page.reload();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  });

  test("app shell: the sidebar follows the language and the choice survives reload and navigation", async ({ page }) => {
    await login(page, "JC-072");
    await expectLanded(page, "/home");
    const menu = page.getByRole("complementary"); // its accessible name is translated too
    await expect(menu.getByRole("link", { name: "Home", exact: true })).toBeVisible();
    await page.getByLabel("Language").selectOption({ label: "हिन्दी" });
    await expect(menu.getByRole("link", { name: "होम", exact: true })).toBeVisible();
    await expect(menu.getByRole("link", { name: "Home", exact: true })).toHaveCount(0);
    await page.reload();
    await expect(menu.getByRole("link", { name: "होम", exact: true })).toBeVisible();
    await page.goto("/leave");
    await expect(menu.getByRole("link", { name: "होम", exact: true })).toBeVisible();
    await page.getByLabel("भाषा").selectOption({ label: "English" });
    await expect(menu.getByRole("link", { name: "Home", exact: true })).toBeVisible();
    await page.reload();
    await expect(menu.getByRole("link", { name: "Home", exact: true })).toBeVisible();
  });
});
