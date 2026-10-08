import type { Metadata } from "next";
import { redirect } from "next/navigation";
import type { PolicyToRead } from "@/lib/api-types";
import { landingPath, requireUser } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { AcknowledgeClient } from "./AcknowledgeClient";

export const metadata: Metadata = { title: "Accept company policies" };

/** Shown instead of every page until the user has accepted the current version of each published policy that needs it. */
export default async function AcknowledgePage() {
  const u = await requireUser();
  if (u.subscriptionActive === false) redirect("/paused");
  const pending = await apiData<PolicyToRead[]>("/api/policies/pending");
  if (!pending.length) redirect(landingPath(u));
  return <AcknowledgeClient policy={pending[0]} left={pending.length} name={u.name} />;
}
