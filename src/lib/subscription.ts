import type { SubscriptionCurrent } from "./api-types";
import { fmtDY } from "./format";

/** Chip class and text for a business's subscription today. */
export function subscriptionChip(s: SubscriptionCurrent | null): { cls: string; text: string } {
  if (!s || s.state === "NONE") return { cls: "neutral", text: "No plan" };
  if (s.state === "UPCOMING") return { cls: "info", text: `Starts ${fmtDY(s.startsOn!)}` };
  if (s.state === "EXPIRED") return { cls: "bad", text: `Ended ${fmtDY(s.endsOn!)}` };
  if (s.daysLeft !== null && s.daysLeft <= 7) return { cls: "warn", text: s.daysLeft === 0 ? "Ends today" : `${s.daysLeft} day${s.daysLeft === 1 ? "" : "s"} left` };
  return { cls: "ok", text: `Until ${fmtDY(s.endsOn!)}` };
}

/** Human names for plan modules. */
export const MODULE_LABELS: Record<string, string> = { JURIS_TRACK: "Juris Track" };
