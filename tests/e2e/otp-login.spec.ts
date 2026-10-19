import { expect, test, type Page } from "@playwright/test";
import { emailOf, expectLanded, OTP, OWNER, sql } from "./helpers";

// Sign-in is by emailed code only. The e2e backend uses the fixed code 000000 (scripts/e2e-env.mjs) and does not
// throttle code requests.
const BAD = "That code is wrong or has expired. Request a new one.";
const box = (page: Page, n: number) => page.getByLabel(`Digit ${n} of 6`);

async function openLogin(page: Page) {
  await page.goto("/login", { waitUntil: "networkidle" });
}

async function requestCode(page: Page, email: string) {
  await page.getByLabel("Email").fill(email);
  await page.getByRole("button", { name: "Send code" }).click();
  await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();
}

test.describe("email code sign-in", () => {
  test.afterAll(async () => {
    await sql(`UPDATE users SET failed_attempts = 0, locked_until = NULL`);
  });

  test("the only way in is the email code: no password field or option", async ({ page }) => {
    await openLogin(page);
    await expect(page.getByRole("button", { name: "Send code" })).toBeVisible();
    await expect(page.getByLabel("Password", { exact: true })).toHaveCount(0);
    await expect(page.getByText(/use password/i)).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Forgot?" })).toHaveCount(0);
  });

  test("the code page shows the masked address, six boxes and the dev hint", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, await emailOf("JC-049"));
    await expect(page.getByText("n•••")).toBeVisible(); // the address is masked
    for (let n = 1; n <= 6; n++) await expect(box(page, n)).toBeVisible();
    await expect(box(page, 1)).toBeFocused();
    await expect(page.getByRole("note")).toContainText(OTP); // only the dev environment shows the fixed code
    await expect(page.getByRole("button", { name: /Resend code in \d+s/ })).toBeDisabled(); // 30-second timer
  });

  test("typing moves between boxes, Backspace goes back, and the sixth digit signs in", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, await emailOf("JC-049"));
    await page.keyboard.type("00");
    await expect(box(page, 3)).toBeFocused();
    await page.keyboard.press("Backspace");
    await expect(box(page, 2)).toBeFocused();
    await expect(box(page, 2)).toHaveValue("");
    await page.keyboard.type("00000"); // boxes 2-6 (box 2 was cleared)
    await expectLanded(page, "/home");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Naman");
  });

  test("pasting the whole code fills every box and signs in", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, await emailOf(OWNER));
    await box(page, 1).fill(OTP);
    await expectLanded(page, "/overview");
  });

  test("a wrong code shows an error and clears the boxes; the right code then works", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, await emailOf("JC-049"));
    await box(page, 1).fill("123456");
    await expect(page.locator(".err")).toHaveText(BAD);
    await expect(box(page, 1)).toHaveValue("");
    await expect(page).toHaveURL(/\/login/);
    await box(page, 1).fill(OTP);
    await expectLanded(page, "/home");
  });

  test("a mistyped email can be corrected from the code page (Change email and Back)", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, "wrong.address@example.com");
    await page.getByRole("button", { name: "Change email" }).click();
    await expect(page.getByLabel("Email")).toHaveValue("wrong.address@example.com"); // filled in, ready to edit
    await page.getByLabel("Email").fill(await emailOf("JC-049"));
    await page.getByRole("button", { name: "Send code" }).click();
    await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();
    await page.getByRole("button", { name: "Back" }).click();
    await expect(page.getByRole("button", { name: "Send code" })).toBeVisible();
    await page.getByRole("button", { name: "Send code" }).click();
    await box(page, 1).fill(OTP);
    await expectLanded(page, "/home");
  });

  test("an unknown email gets the same next step, and no code ever works for it", async ({ page }) => {
    await openLogin(page);
    await requestCode(page, "nobody.at.all@example.com"); // generic: same page as for a real account
    await box(page, 1).fill(OTP);
    await expect(page.locator(".err")).toHaveText(BAD);
    await expect(page).toHaveURL(/\/login/);
  });

  test("an invalid email is refused before any code is sent", async ({ page }) => {
    await openLogin(page);
    await page.getByLabel("Email").fill("not-an-email");
    await page.getByRole("button", { name: "Send code" }).click();
    await expect(page.locator(".err")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Check your email" })).toHaveCount(0);
  });
});
