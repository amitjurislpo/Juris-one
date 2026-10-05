import { hash, verify } from "@node-rs/argon2";

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

export const PASSWORD_RULES = "At least 10 characters, with a letter and a number or symbol.";
export function passwordProblem(pw: string): string | null {
  if (pw.length < 10) return "Use at least 10 characters.";
  if (pw.length > 200) return "That password is too long.";
  if (!/[A-Za-z]/.test(pw) || !/[^A-Za-z]/.test(pw)) return "Include at least one letter and one number or symbol.";
  return null;
}
