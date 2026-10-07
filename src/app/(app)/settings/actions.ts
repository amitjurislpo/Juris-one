"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

export interface AttendancePolicyInput {
  fullDayHours: number;
  halfDayHours: number;
  graceMinutes: number;
  lateMarksPerHalfDayLop: number;
  weeklyOffs: number[];
  absenceJobBufferMinutes: number;
  lockDayOfNextMonth: number;
  lockTime: string;
  trackingStartDate: string;
}

/** Attendance policy (REQUIREMENTS §5.4 "configurable in Policies") — saved by the API. Locked months keep their frozen values. */
export async function updateAttendancePolicy(input: AttendancePolicyInput): Promise<ActionResult> {
  return apiAction("PUT", "/api/settings/attendance", input, ["/settings", "/home", "/attendance", "/register", "/team"]);
}
