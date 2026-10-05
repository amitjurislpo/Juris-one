import { redirect } from "next/navigation";
import { getCurrentUser, landingPath } from "@/lib/auth";

export default async function Root() {
  const u = await getCurrentUser();
  redirect(u ? landingPath(u) : "/login");
}
