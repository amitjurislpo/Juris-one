import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "tests/e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000, // dev server compiles routes on first hit
  reporter: [["list"]],
  use: { baseURL: "http://localhost:3005", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: { command: "npm run dev", url: "http://localhost:3005/login", reuseExistingServer: true, timeout: 120_000 },
});
