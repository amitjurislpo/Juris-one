import { describe, expect, it } from "vitest";
import { decryptPII, encryptPII, last4, sha256 } from "@/lib/crypto";
import { maskAadhaar, maskPan } from "@/lib/mask";

describe("PII encryption", () => {
  it("round-trips and never stores plaintext", () => {
    const aadhaar = "234567891234";
    const enc = encryptPII(aadhaar)!;
    expect(enc).not.toContain(aadhaar);
    expect(enc.startsWith("v1.")).toBe(true);
    expect(decryptPII(enc)).toBe(aadhaar);
  });

  it("uses a fresh IV each time", () => {
    expect(encryptPII("ABCDE1234F")).not.toBe(encryptPII("ABCDE1234F"));
  });

  it("rejects tampered ciphertext", () => {
    const enc = encryptPII("ABCDE1234F")!;
    const parts = enc.split(".");
    parts[3] = Buffer.from("tampered").toString("base64url");
    expect(() => decryptPII(parts.join("."))).toThrow();
  });

  it("treats empty values as null", () => {
    expect(encryptPII("")).toBeNull();
    expect(decryptPII(null)).toBeNull();
  });

  it("masks to the last four only", () => {
    expect(last4("2345 6789 1234")).toBe("1234");
    expect(maskAadhaar(last4("234567891234"))).toBe("XXXX XXXX 1234");
    expect(maskPan(last4("ABCDE1234F"))).toBe("XXXXXX234F");
  });

  it("hashes tokens deterministically", () => {
    expect(sha256("abc")).toHaveLength(64);
    expect(sha256("abc")).toBe(sha256("abc"));
  });
});
