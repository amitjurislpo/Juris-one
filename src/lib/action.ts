import "server-only";
import { ZodError } from "zod";
import { ForbiddenError } from "./auth";

export type ActionResult<T = undefined> = { ok: true; msg?: string; data?: T } | { ok: false; error: string };

/** Problems the user can fix (validation, business rules). Shown as-is. */
export class UserError extends Error {}

/**
 * Wraps a server action body: permission / validation / business errors become `{ ok: false, error }`,
 * anything unexpected is logged and reported generically.
 */
export async function run<T>(fn: () => Promise<ActionResult<T>>): Promise<ActionResult<T>> {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof ForbiddenError || e instanceof UserError) return { ok: false, error: e.message };
    if (e instanceof ZodError) return { ok: false, error: e.issues[0]?.message ?? "Some details are invalid." };
    // Prisma unique violation
    if (typeof e === "object" && e && "code" in e && (e as { code: string }).code === "P2002") return { ok: false, error: "That already exists." };
    console.error("[action]", e);
    return { ok: false, error: "Something went wrong. Please try again." };
  }
}
