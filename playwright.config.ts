import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests run against their own environment (scripts/e2e-env.mjs): database "<db>_e2e" reset and seeded
 * with the demo business on every run, the API on :8081 and a production build of the app on :3006. Development data
 * is never touched. `E2E_BUILD=1` rebuilds the backend jar and the app first.
 */
export default defineConfig({
  testDir: "tests/e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  reporter: [["list"]],
  use: { baseURL: "http://localhost:3006", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    { command: "node scripts/e2e-env.mjs backend", url: "http://127.0.0.1:8081/actuator/health", reuseExistingServer: false, timeout: 600_000 },
    { command: "node scripts/e2e-env.mjs frontend", url: "http://localhost:3006/login", reuseExistingServer: false, timeout: 600_000 },
  ],
});
