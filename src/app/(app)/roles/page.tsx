import type { Metadata } from "next";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { RolesClient, type RoleView } from "./RolesClient";

export const metadata: Metadata = { title: "Roles & permissions" };

export default async function RolesPage(props: PageProps<"/roles">) {
  await requirePage("roles");
  const sp = await props.searchParams;
  const view = await apiData<RoleView[]>("/api/roles");
  const sel = typeof sp.role === "string" && view.some((r) => r.id === sp.role) ? sp.role : (view.find((r) => r.key === "hrm") ?? view[0]).id;
  return <RolesClient roles={view} initialSel={sel} />;
}
