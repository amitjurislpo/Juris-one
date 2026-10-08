import { z } from "zod";
import { digits, LOCATIONS, PAN_RE, validAadhaar, validUan } from "./people-rules";
import { phoneProblem } from "./phone";

/**
 * Add / edit employee (REQUIREMENTS §5.2). Shared by the 3-step form and the server action.
 * On edit, blank PII fields mean "keep what's on file". PAN, Aadhaar and UAN are optional (checked when given).
 */
export const employeeInput = z.object({
  // step 1 · personal
  firstName: z.string().trim().min(1, "First name is required").max(60),
  middleName: z.string().trim().max(60),
  lastName: z.string().trim().min(1, "Last name is required").max(60),
  fatherName: z.string().trim().min(2, "Father’s name is required").max(120),
  email: z.string().trim().toLowerCase().max(200).email("Enter a valid email"),
  phoneCountry: z.string().trim().max(5),
  phone: z.string().trim().max(20),
  /** empty = India (IST) */
  location: z.union([z.enum(LOCATIONS.map((l) => l.v) as [string, ...string[]]), z.literal("")]),
  joiningOffice: z.string().trim().max(120),
  // step 2 · identity & address (India only for IDs)
  pan: z.string().trim().toUpperCase().max(10),
  aadhaar: z.string().trim().max(20),
  uan: z.string().trim().max(20),
  address: z.string().trim().max(500),
  // step 3 · job & access
  designation: z.string().trim().min(2, "Designation is required").max(120),
  departmentId: z.string().uuid("Pick a department"),
  managerId: z.union([z.string().uuid(), z.literal("")]),
  doj: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Pick the date of joining"),
  shift: z.enum(["IN_NIGHT", "IN_DAY", "US_DAY"]),
  roleId: z.string().uuid("Pick a role"),
});

export type EmployeeInput = z.infer<typeof employeeInput>;

export type StepErrors = Partial<Record<keyof EmployeeInput, string>>;

/** Step-by-step checks shown in the form (and re-run on the server). */
export function stepErrors(step: 1 | 2 | 3, f: EmployeeInput, editing: boolean, hadManager = false): StepErrors {
  const x: StepErrors = {};
  const india = !f.location || f.location === "IN_IST";
  if (step === 1) {
    if (!f.firstName.trim()) x.firstName = "First name is required";
    if (!f.lastName.trim()) x.lastName = "Last name is required";
    if (f.fatherName.trim().length < 2) x.fatherName = "Father’s name is required";
    if (!z.string().email().safeParse(f.email.trim()).success) x.email = "Enter a valid email";
    if (f.phone || !editing) { const p = phoneProblem(f.phoneCountry, f.phone); if (p) x.phone = p; }
  }
  if (step === 2 && india) {
    if (f.pan && !PAN_RE.test(f.pan.trim().toUpperCase())) x.pan = "PAN looks like ABCDE1234F";
    if (f.aadhaar && !validAadhaar(f.aadhaar)) x.aadhaar = "Aadhaar has 12 digits";
    if (f.uan && !validUan(f.uan)) x.uan = "UAN has 12 digits";
  }
  if (step === 3) {
    if (f.designation.trim().length < 2) x.designation = "Designation is required";
    if (!f.departmentId) x.departmentId = "Pick a department";
    if (!f.managerId && (!editing || hadManager)) x.managerId = "Pick a reporting manager";
    if (!f.doj) x.doj = "Pick the date of joining";
    if (!f.roleId) x.roleId = "Pick a role";
  }
  return x;
}

export const cleanAadhaar = (s: string) => digits(s);
export const cleanUan = (s: string) => digits(s);
