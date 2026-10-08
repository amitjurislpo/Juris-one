import { hash, verify } from "@node-rs/argon2";

// Hashing helpers for tests and tooling (the API hashes passwords in production; same argon2id parameters).

// argon2id (algorithm 2) with OWASP-recommended parameters.
const OPTS = { algorithm: 2 as const, memoryCost: 19456, timeCost: 2, parallelism: 1 };

export const hashPassword = (pw: string) => hash(pw, OPTS);

export async function verifyPassword(stored: string | null | undefined, pw: string): Promise<boolean> {
  if (!stored) return false;
  try {
    return await verify(stored, pw);
  } catch {
    return false;
  }
}

/** A hash to verify against when the account doesn't exist, so timing doesn't reveal it. */
let dummy: Promise<string> | null = null;
export const dummyHash = () => (dummy ??= hashPassword("timing-equaliser-not-a-real-password"));

export { PASSWORD_RULES, passwordProblem } from "./password-rules";
