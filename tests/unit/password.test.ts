import { describe, expect, it } from "vitest";
import { hashPassword, passwordProblem, verifyPassword } from "@/lib/password";

describe("passwords", () => {
  it("hashes with argon2id and verifies", async () => {
    const h = await hashPassword("admin@jurislpo");
    expect(h.startsWith("$argon2id$")).toBe(true);
    expect(await verifyPassword(h, "admin@jurislpo")).toBe(true);
    expect(await verifyPassword(h, "wrong-password")).toBe(false);
    expect(await verifyPassword(null, "x")).toBe(false);
  });

  it("enforces the policy", () => {
    expect(passwordProblem("short1")).toMatch(/10 characters/);
    expect(passwordProblem("onlyletters")).toMatch(/number or symbol/);
    expect(passwordProblem("admin@jurislpo")).toBeNull();
    expect(passwordProblem("Juris@2026x")).toBeNull();
  });
});
