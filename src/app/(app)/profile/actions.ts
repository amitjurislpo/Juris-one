"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

export interface ProfileInput {
  fatherName: string; dateOfBirth: string; currentAddress: string; permanentAddress: string; emergencyName: string; emergencyRelation: string;
  emergencyPhoneCountry: string; emergencyPhone: string;
}

/** Saves the details people edit themselves (everything else is changed by HR). */
export async function saveMyProfile(i: ProfileInput): Promise<ActionResult> {
  return apiAction("PUT", "/api/profile", i, ["/profile"]);
}
