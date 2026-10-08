import { describe, expect, it } from "vitest";
import { stepErrors, type EmployeeInput } from "@/lib/employee-schema";
import { canAddEmployees, canAssignRole, canEditEmployee, canToggleAccess, createsCycle, derivedStatus, initialStatus, lockedDepartment, validAadhaar, type Actor, type Target } from "@/lib/people-rules";

const R = { owner: "r-owner", hrm: "r-hrm", hre: "r-hre", fm: "r-fm", fe: "r-fe", emp: "r-emp" };
const actor = (key: keyof typeof R, perms: string[], assign: (keyof typeof R)[], dept: string | null = null, id = "me"): Actor => ({
  id, roleKey: key === "emp" ? "employee" : key, perms: new Set(perms), assignable: new Set(assign.map((a) => R[a])), departmentId: dept, employeeRoleId: R.emp,
});
const target = (key: keyof typeof R, over: Partial<Target> = {}): Target => ({ id: "t", roleId: R[key], roleKey: key === "emp" ? "employee" : key, status: "ACTIVE", isSuperAdmin: false, ...over });

const ADMIN = actor("owner", ["people.add", "access.toggle", "people.view"], ["hrm", "fm", "hre", "fe", "emp"]);
const HRM = actor("hrm", ["people.add", "access.toggle", "people.view"], ["hre", "emp"], "d-hr");
const FM = actor("fm", ["people.add", "access.toggle", "people.view"], ["fe"], "d-fin");
const HRE = actor("hre", ["people.view"], []);

describe("editing employees", () => {
  it("HR Manager edits HR Executives and Employees only", () => {
    expect(canEditEmployee(HRM, target("emp"))).toBe(true);
    expect(canEditEmployee(HRM, target("hre"))).toBe(true);
    expect(canEditEmployee(HRM, target("fe"))).toBe(false);
    expect(canEditEmployee(HRM, target("owner"))).toBe(false);
  });
  it("Admin edits anyone except the super admin; nobody edits themselves", () => {
    expect(canEditEmployee(ADMIN, target("hrm"))).toBe(true);
    expect(canEditEmployee(ADMIN, target("owner", { isSuperAdmin: true }))).toBe(false);
    expect(canEditEmployee(ADMIN, target("owner", { id: "me" }))).toBe(false);
  });
  it("needs people.add", () => {
    expect(canEditEmployee(HRE, target("emp"))).toBe(false);
    expect(canAddEmployees(HRE)).toBe(false);
    expect(canAddEmployees(FM)).toBe(true);
  });
});

describe("access toggle", () => {
  it("never yourself, never super admin, never exited", () => {
    expect(canToggleAccess(HRM, target("emp", { id: "me" }))).toBe(false);
    expect(canToggleAccess(ADMIN, target("owner", { isSuperAdmin: true }))).toBe(false);
    expect(canToggleAccess(HRM, target("emp", { status: "EXITED" }))).toBe(false);
  });
  it("only Admin can toggle an Admin", () => {
    expect(canToggleAccess(HRM, target("owner"))).toBe(false);
    expect(canToggleAccess(ADMIN, target("owner"))).toBe(true);
    expect(canToggleAccess(HRM, target("fm"))).toBe(true);
  });
  it("needs access.toggle", () => expect(canToggleAccess(HRE, target("emp"))).toBe(false));
});

describe("roles and departments", () => {
  it("assigns only allowed roles, but keeping the current role is fine", () => {
    expect(canAssignRole(FM, R.fe)).toBe(true);
    expect(canAssignRole(FM, R.emp)).toBe(false);
    expect(canAssignRole(FM, R.emp, R.emp)).toBe(true);
  });
  it("locks the Finance Manager to Finance; HR and Admin choose freely", () => {
    expect(lockedDepartment(FM)).toBe("d-fin");
    expect(lockedDepartment(HRM)).toBeNull();
    expect(lockedDepartment(ADMIN)).toBeNull();
  });
});

describe("status and reporting lines", () => {
  it("new hires are Onboarding until they join, then Probation", () => {
    expect(initialStatus("2026-10-12", "2026-10-06")).toBe("ONBOARDING");
    expect(initialStatus("2026-10-06", "2026-10-06")).toBe("PROBATION");
  });
  it("becomes Active after the probation months", () => {
    expect(derivedStatus("PROBATION", "2026-03-02", "2026-09-01", 6)).toBe("PROBATION");
    expect(derivedStatus("PROBATION", "2026-03-02", "2026-09-02", 6)).toBe("ACTIVE");
    expect(derivedStatus("ONBOARDING", "2026-10-06", "2026-10-06", 6)).toBe("PROBATION");
    expect(derivedStatus("EXITED", "2020-01-01", "2026-10-06", 6)).toBe("EXITED");
  });
  it("detects reporting loops", () => {
    const m = new Map<string, string | null>([["a", null], ["b", "a"], ["c", "b"]]);
    expect(createsCycle("a", "c", m)).toBe(true); // a → c → b → a
    expect(createsCycle("c", "a", m)).toBe(false);
  });
});

describe("form validation", () => {
  const base: EmployeeInput = { firstName: "Meera", middleName: "", lastName: "Joshi", fatherName: "Prakash Joshi", email: "meera.joshi@jurislpo.com", phoneCountry: "+91", phone: "9876543210", location: "IN_IST", joiningOffice: "", pan: "ABCDE1234F", aadhaar: "2345 6789 0123", uan: "", address: "", designation: "Associate", departmentId: "x", managerId: "y", doj: "2026-10-12", shift: "IN_NIGHT", roleId: "r" };
  it("PAN and Aadhaar are optional, but checked when given", () => {
    expect(stepErrors(2, { ...base, pan: "", aadhaar: "" }, false)).toEqual({});
    expect(stepErrors(2, { ...base, pan: "abcde1234f" }, false).pan).toBeUndefined();
    expect(stepErrors(2, { ...base, pan: "ABC" }, false).pan).toBeDefined();
    expect(stepErrors(2, { ...base, aadhaar: "1234" }, false).aadhaar).toBeDefined();
    expect(stepErrors(2, { ...base, aadhaar: "1234 1234 1234" }, false).aadhaar).toBeUndefined();
    expect(stepErrors(2, { ...base, uan: "123" }, false).uan).toBeDefined();
  });
  it("needs first and last name, a valid email and a phone that fits its country", () => {
    expect(stepErrors(1, base, false)).toEqual({});
    expect(stepErrors(1, { ...base, lastName: "" }, false).lastName).toBeDefined();
    expect(stepErrors(1, { ...base, firstName: "" }, false).firstName).toBeDefined();
    expect(stepErrors(1, { ...base, email: "meera@" }, false).email).toBeDefined();
    expect(stepErrors(1, { ...base, phone: "987654321" }, false).phone).toBe("India numbers have 10 digits");
    expect(stepErrors(1, { ...base, phoneCountry: "+65", phone: "91234567" }, false)).toEqual({});
  });
  it("skips Indian IDs for the US team, keeps blanks on edit, no location = India", () => {
    expect(stepErrors(2, { ...base, location: "US_ET", pan: "bad", aadhaar: "bad" }, false)).toEqual({});
    expect(stepErrors(2, { ...base, location: "", aadhaar: "1" }, false).aadhaar).toBeDefined();
    expect(stepErrors(1, { ...base, phone: "" }, true)).toEqual({});
  });
  it("validates Aadhaar shape", () => {
    expect(validAadhaar("2345 6789 0123")).toBe(true);
    expect(validAadhaar("1234 1234 1234")).toBe(true);
    expect(validAadhaar("1234 1234 123")).toBe(false);
  });
});
