import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";

/**
 * Application-level encryption for PII (PAN, Aadhaar, UAN, phone, address, bank account).
 * AES-256-GCM; stored as "v1.<iv>.<tag>.<ciphertext>" (base64url parts).
 * Pure Node — usable from the app, the seed script and tests.
 */
function key(): Buffer {
  const raw = process.env.PII_ENCRYPTION_KEY ?? "";
  const k = Buffer.from(raw, "base64");
  if (k.length !== 32) throw new Error("PII_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
  return k;
}

export function encryptPII(plain: string | null | undefined): string | null {
  if (plain == null || plain === "") return null;
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const ct = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return ["v1", iv.toString("base64url"), tag.toString("base64url"), ct.toString("base64url")].join(".");
}

export function decryptPII(stored: string | null | undefined): string | null {
  if (!stored) return null;
  const [v, iv, tag, ct] = stored.split(".");
  if (v !== "v1" || !iv || !tag || !ct) throw new Error("Unrecognised PII ciphertext");
  const decipher = createDecipheriv("aes-256-gcm", key(), Buffer.from(iv, "base64url"));
  decipher.setAuthTag(Buffer.from(tag, "base64url"));
  return Buffer.concat([decipher.update(Buffer.from(ct, "base64url")), decipher.final()]).toString("utf8");
}

/** Last four significant characters (digits/letters), for masked display. */
export function last4(value: string | null | undefined): string | null {
  if (!value) return null;
  const clean = value.replace(/[^0-9A-Za-z]/g, "");
  return clean.length ? clean.slice(-4) : null;
}

export const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

export const randomToken = (bytes = 32) => randomBytes(bytes).toString("base64url");
