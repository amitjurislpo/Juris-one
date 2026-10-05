/**
 * Seed — idempotent (safe to re-run).
 *   Core:  roles & permissions, shifts, leave types, FY 2026-27 tax tables, policy settings,
 *          Human Resources + Finance departments, the super admin (admin@jurislpo.com).
 *   Demo:  REQUIREMENTS §8 employees, demo departments, 2026 holidays, salary structures,
 *          tax profiles and declarations from the prototype. Skip with SEED_DEMO=false.
 */
import "dotenv/config";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient, type EmployeeStatus, type HolidayRegion, type Region, type StructureReason } from "../src/generated/prisma/client";
import { encryptPII, last4 } from "../src/lib/crypto";
import { hashPassword } from "../src/lib/password";
import { DEFAULT_SETTINGS } from "../src/lib/settings-defaults";

const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: process.env.DATABASE_URL! }) });
const d = (iso: string) => new Date(`${iso}T00:00:00.000Z`);
const paise = (rupees: number) => Math.round(rupees * 100);

// ───────────────────────── roles ─────────────────────────
const ALL_PERMS = ["self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage", "pay.view", "pay.close", "struct.manage", "tax.view", "proofs.verify", "roles.manage"];
const ROLES: { key: string; name: string; locked?: boolean; perms: string[]; assign: string[] }[] = [
  { key: "admin", name: "Admin", locked: true, perms: ALL_PERMS.filter((p) => p !== "self"), assign: ["hrm", "fm", "hre", "fe", "employee"] },
  { key: "hrm", name: "HR Manager", perms: ["self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage"], assign: ["hre", "employee"] },
  { key: "hre", name: "HR Executive", perms: ["self", "dir", "att.all", "appr.leave", "appr.reg", "people.view"], assign: [] },
  { key: "fm", name: "Finance Manager", perms: ["self", "dir", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "pay.view", "pay.close", "struct.manage", "tax.view", "proofs.verify"], assign: ["fe"] },
  { key: "fe", name: "Finance Executive", perms: ["self", "dir", "appr.reg", "pay.view", "tax.view", "proofs.verify"], assign: [] },
  { key: "employee", name: "Employee", perms: ["self", "dir"], assign: [] },
];

async function seedRoles() {
  const ids: Record<string, string> = {};
  for (const r of ROLES) {
    const row = await db.role.upsert({
      where: { key: r.key },
      create: { key: r.key, name: r.name, isBuiltin: true, isLocked: !!r.locked },
      update: { name: r.name, isBuiltin: true, isLocked: !!r.locked },
    });
    ids[r.key] = row.id;
  }
  for (const r of ROLES) {
    // Defaults are only written when a role has no permissions yet — Admin edits survive re-seeding.
    const has = await db.rolePermission.count({ where: { roleId: ids[r.key] } });
    if (!has || r.locked) {
      await db.rolePermission.deleteMany({ where: { roleId: ids[r.key] } });
      await db.rolePermission.createMany({ data: r.perms.map((p) => ({ roleId: ids[r.key], permissionKey: p })) });
    }
    const hasAssign = await db.roleAssignableRole.count({ where: { roleId: ids[r.key] } });
    if (!hasAssign || r.locked) {
      await db.roleAssignableRole.deleteMany({ where: { roleId: ids[r.key] } });
      await db.roleAssignableRole.createMany({ data: r.assign.map((a) => ({ roleId: ids[r.key], assignableRoleId: ids[a] })) });
    }
  }
  return ids;
}

// ───────────────────────── shifts ─────────────────────────
async function seedShifts() {
  const S = [
    { code: "IN_NIGHT", name: "India Night", startLocal: "19:30", endLocal: "04:30", region: "IN" as Region, crossesMidnight: true },
    { code: "IN_DAY", name: "India Day", startLocal: "09:30", endLocal: "18:30", region: "IN" as Region, crossesMidnight: false },
    { code: "US_DAY", name: "US Day", startLocal: "09:00", endLocal: "18:00", region: "US" as Region, crossesMidnight: false },
  ];
  const ids: Record<string, string> = {};
  for (const s of S) {
    const row = await db.shift.upsert({ where: { code: s.code }, create: { ...s, graceMinutes: 15 }, update: { name: s.name, startLocal: s.startLocal, endLocal: s.endLocal, crossesMidnight: s.crossesMidnight } });
    ids[s.code] = row.id;
  }
  return ids;
}

// ───────────────────────── leave types ─────────────────────────
async function seedLeaveTypes() {
  const T = [
    { code: "CL", name: "Casual leave", annualQuota: 12, accrualPerMonth: 1, carryForwardCap: 0, maxStretch: 3, sortOrder: 1 },
    { code: "EL", name: "Earned leave", annualQuota: 18, accrualPerMonth: 1.5, carryForwardCap: 30, encashPerYear: 15, encashAfterMonths: 12, sortOrder: 2 },
    { code: "CO", name: "Comp-off", annualQuota: 0, accrualPerMonth: 0, carryForwardCap: 0, expiryDays: 60, sortOrder: 3 },
    { code: "LOP", name: "Leave without pay", annualQuota: 0, accrualPerMonth: 0, carryForwardCap: 0, paid: false, needsBalance: false, sortOrder: 4 },
  ];
  for (const t of T) await db.leaveType.upsert({ where: { code: t.code }, create: t, update: {} });
}

// ───────────────────────── tax (FY 2026-27 — Finance/CA must verify) ─────────────────────────
async function seedTax() {
  const fy = "2026-27";
  const surcharge = [
    { above: 5000000, rate: 0.1 },
    { above: 10000000, rate: 0.15 },
    { above: 20000000, rate: 0.25 },
  ];
  await db.taxYear.upsert({
    where: { fy_regime: { fy, regime: "NEW" } },
    create: {
      fy, regime: "NEW", stdDeduction: 75000, rebateLimit: 1200000, marginalRelief: true, cessRate: 0.04,
      slabs: [{ upto: 400000, rate: 0 }, { upto: 800000, rate: 0.05 }, { upto: 1200000, rate: 0.1 }, { upto: 1600000, rate: 0.15 }, { upto: 2000000, rate: 0.2 }, { upto: 2400000, rate: 0.25 }, { upto: null, rate: 0.3 }],
      surcharge, // new regime caps surcharge at 25%
    },
    update: {},
  });
  await db.taxYear.upsert({
    where: { fy_regime: { fy, regime: "OLD" } },
    create: {
      fy, regime: "OLD", stdDeduction: 50000, rebateLimit: 500000, marginalRelief: false, cessRate: 0.04,
      slabs: [{ upto: 250000, rate: 0 }, { upto: 500000, rate: 0.05 }, { upto: 1000000, rate: 0.2 }, { upto: null, rate: 0.3 }],
      surcharge: [...surcharge, { above: 50000000, rate: 0.37 }],
    },
    update: {},
  });
  const SECTIONS = [
    { key: "HRA", label: "HRA", cap: null },
    { key: "80C", label: "80C", cap: 150000 },
    { key: "80CCD1B", label: "80CCD(1B)", cap: 50000 },
    { key: "80D_SELF", label: "80D · self & family", cap: 25000 },
    { key: "80D_PARENTS", label: "80D · parents", cap: 25000 },
    { key: "80D_PARENTS_SENIOR", label: "80D · senior-citizen parents", cap: 50000 },
    { key: "24B", label: "24(b)", cap: 200000 },
    { key: "80E", label: "80E", cap: null },
    { key: "80G", label: "80G", cap: null },
    { key: "80TTA", label: "80TTA", cap: 10000 },
  ];
  for (const [i, s] of SECTIONS.entries()) await db.taxSection.upsert({ where: { fy_key: { fy, key: s.key } }, create: { fy, ...s, sortOrder: i }, update: {} });
}

async function seedSettings() {
  for (const [key, value] of Object.entries(DEFAULT_SETTINGS)) {
    await db.setting.upsert({ where: { key }, create: { key, value: value as object }, update: {} });
  }
}

// ───────────────────────── employees ─────────────────────────
interface Seed {
  code: string;
  name: string;
  title: string;
  dept: string | null;
  manager: string | null;
  doj: string;
  role: string;
  status: EmployeeStatus;
  shift: "IN_NIGHT" | "IN_DAY" | "US_DAY" | null;
  tz: string;
  father: string | null;
  exit?: string;
  access?: boolean;
  email?: string;
}

const LOC_REGION = (tz: string): Region => (tz === "Asia/Kolkata" ? "IN" : "US");

/** Deterministic fake IDs so demo data is stable across re-seeds. */
function fakeIds(code: string, india: boolean) {
  const n = Number(code.replace(/\D/g, ""));
  const pad = (x: number, w: number) => String(x).padStart(w, "0");
  const letters = "ABCDEFGHJKLMNPQRSTUVWXYZ";
  const L = (i: number) => letters[(n * 7 + i * 5) % letters.length];
  return {
    phone: india ? `+91 98${pad((n * 7919) % 1000, 3)} ${pad((n * 104729) % 100000, 5)}` : `+1 (212) 555-${pad((n * 37) % 10000, 4)}`,
    pan: india ? `${L(1)}${L(2)}${L(3)}P${L(4)}${pad((n * 1231) % 10000, 4)}${L(5)}` : null,
    aadhaar: india ? `${pad(2000 + ((n * 3571) % 7000), 4)}${pad((n * 6007) % 10000, 4)}${pad((n * 9001) % 10000, 4)}` : null,
    uan: india ? `1009${pad((n * 4421) % 100000000, 8)}` : null,
    bank: india ? `5010${pad((n * 77777) % 100000000, 8)}` : null,
    address: india ? `${(n % 90) + 10}, Sector ${(n % 60) + 1}, Noida, Uttar Pradesh 201301` : `${100 + n} Park Avenue, New York, NY 10017`,
  };
}

const ADMIN_EMAIL = (process.env.SUPER_ADMIN_EMAIL || "admin@jurislpo.com").toLowerCase();

const PEOPLE: Seed[] = [
  { code: "JC-002", name: "Sana Kapoor", title: "Portal Administrator", dept: null, manager: null, doj: "2021-04-12", role: "admin", status: "ACTIVE", shift: null, tz: "Asia/Kolkata", father: "Rajeev Kapoor" },
  { code: "JC-004", name: "Arjun Mehta", title: "Finance Manager", dept: "Finance", manager: null, doj: "2020-07-01", role: "fm", status: "ACTIVE", shift: "IN_DAY", tz: "Asia/Kolkata", father: "Suresh Mehta" },
  { code: "JC-012", name: "Riya Malhotra", title: "HR Manager", dept: "Human Resources", manager: null, doj: "2025-08-04", role: "hrm", status: "ACTIVE", shift: "IN_DAY", tz: "Asia/Kolkata", father: "Anil Malhotra" },
  { code: "JC-015", name: "Pooja Nair", title: "HR Executive", dept: "Human Resources", manager: "JC-012", doj: "2026-01-12", role: "hre", status: "ACTIVE", shift: "IN_DAY", tz: "Asia/Kolkata", father: "Mohan Nair" },
  { code: "JC-018", name: "Rohan Gupta", title: "Finance Executive", dept: "Finance", manager: "JC-004", doj: "2025-11-03", role: "fe", status: "ACTIVE", shift: "IN_DAY", tz: "Asia/Kolkata", father: "Vinod Gupta" },
  { code: "JC-030", name: "Vikas Rao", title: "Legal Researcher", dept: "Litigation Support", manager: "JC-035", doj: "2023-02-01", role: "employee", status: "EXITED", shift: "IN_NIGHT", tz: "Asia/Kolkata", father: "Prakash Rao", exit: "2026-08-31", access: false },
  { code: "JC-035", name: "Nazneen Khan", title: "Engineering Manager", dept: "Technology", manager: "JC-004", doj: "2022-02-14", role: "employee", status: "ACTIVE", shift: "IN_NIGHT", tz: "Asia/Kolkata", father: "Imran Khan" },
  { code: "JC-049", name: "Naman Tyagi", title: "Litigation Analyst", dept: "Litigation Support", manager: "JC-035", doj: "2024-11-18", role: "employee", status: "ACTIVE", shift: "IN_NIGHT", tz: "Asia/Kolkata", father: "Rakesh Tyagi" },
  { code: "JC-058", name: "Kavya Iyer", title: "Contract Analyst", dept: "Contract Management", manager: "JC-035", doj: "2023-06-05", role: "employee", status: "ACTIVE", shift: "IN_NIGHT", tz: "Asia/Kolkata", father: "Ramesh Iyer" },
  { code: "JC-072", name: "Siddharth Krishna", title: "Document Reviewer", dept: "Document Review", manager: "JC-035", doj: "2025-01-20", role: "employee", status: "ACTIVE", shift: "IN_NIGHT", tz: "Asia/Kolkata", father: "Gopal Krishna" },
  { code: "JC-074", name: "Amit Jaspal", title: "Tech Lead", dept: "Technology", manager: "JC-035", doj: "2026-09-08", role: "employee", status: "PROBATION", shift: "IN_NIGHT", tz: "Asia/Kolkata", father: "Harish Jaspal" },
  { code: "JC-081", name: "Farhan Siddiqui", title: "Paralegal", dept: "Litigation Support", manager: "JC-035", doj: "2026-03-02", role: "employee", status: "PROBATION", shift: "IN_NIGHT", tz: "Asia/Kolkata", father: "Salim Siddiqui", access: false },
  { code: "JC-091", name: "Daniel Brooks", title: "Client Services Lead", dept: "Client Services", manager: "JC-002", doj: "2024-09-09", role: "employee", status: "ACTIVE", shift: "US_DAY", tz: "America/Los_Angeles", father: null },
  { code: "JC-090", name: "Emily Carter", title: "Client Success Associate", dept: "Client Services", manager: "JC-091", doj: "2025-05-19", role: "employee", status: "ACTIVE", shift: "US_DAY", tz: "America/New_York", father: null },
];

const SUPER_ADMIN: Seed = { code: "JC-001", name: "JurisOne Administrator", title: "Super Administrator", dept: null, manager: null, doj: "2020-06-01", role: "admin", status: "ACTIVE", shift: null, tz: "Asia/Kolkata", father: null, email: ADMIN_EMAIL };

async function upsertEmployee(p: Seed, roleIds: Record<string, string>, shiftIds: Record<string, string>, deptIds: Record<string, string>, codeToId: Record<string, string>, createdBy: string | null) {
  const india = p.tz === "Asia/Kolkata";
  const ids = fakeIds(p.code, india);
  const email = p.email ?? `${p.name.toLowerCase().split(" ").join(".")}@jurislpo.com`;
  const data = {
    fullName: p.name,
    fatherName: p.father,
    email,
    designation: p.title,
    roleId: roleIds[p.role],
    departmentId: p.dept ? deptIds[p.dept] : null,
    reportingManagerId: p.manager ? codeToId[p.manager] ?? null : null,
    dateOfJoining: d(p.doj),
    exitDate: p.exit ? d(p.exit) : null,
    status: p.status,
    timeZone: p.tz,
    region: LOC_REGION(p.tz),
    shiftId: p.shift ? shiftIds[p.shift] : null,
    portalAccess: p.status === "EXITED" ? false : p.access !== false,
  };
  const existing = await db.employee.findUnique({ where: { code: p.code } });
  if (existing) {
    await db.employee.update({ where: { id: existing.id }, data: { reportingManagerId: data.reportingManagerId } });
    return existing.id;
  }
  const row = await db.employee.create({
    data: {
      code: p.code,
      ...data,
      createdById: createdBy,
      phoneEnc: encryptPII(ids.phone), phoneLast4: last4(ids.phone),
      addressEnc: encryptPII(ids.address),
      panEnc: encryptPII(ids.pan), panLast4: last4(ids.pan),
      aadhaarEnc: encryptPII(ids.aadhaar), aadhaarLast4: last4(ids.aadhaar),
      uanEnc: encryptPII(ids.uan), uanLast4: last4(ids.uan),
      bankAccountEnc: encryptPII(ids.bank), bankAccountLast4: last4(ids.bank),
      bankName: india ? "HDFC Bank" : null, bankIfsc: india ? "HDFC0001234" : null,
    },
  });
  return row.id;
}

async function setPassword(employeeId: string, password: string | undefined, superAdmin = false) {
  const existing = await db.user.findUnique({ where: { employeeId } });
  if (existing?.passwordHash) {
    if (superAdmin && !existing.isSuperAdmin) await db.user.update({ where: { employeeId }, data: { isSuperAdmin: true } });
    return; // never overwrite a password someone has already set
  }
  await db.user.upsert({
    where: { employeeId },
    create: { employeeId, passwordHash: password ? await hashPassword(password) : null, passwordSetAt: password ? new Date() : null, isSuperAdmin: superAdmin },
    update: { passwordHash: password ? await hashPassword(password) : null, passwordSetAt: password ? new Date() : null, isSuperAdmin: superAdmin },
  });
}

async function main() {
  const roleIds = await seedRoles();
  const shiftIds = await seedShifts();
  await seedLeaveTypes();
  await seedTax();
  await seedSettings();

  // Super admin first — it "creates" the two core departments.
  const deptIds: Record<string, string> = {};
  const codeToId: Record<string, string> = {};
  codeToId[SUPER_ADMIN.code] = await upsertEmployee(SUPER_ADMIN, roleIds, shiftIds, deptIds, codeToId, null);
  const adminPw = process.env.SUPER_ADMIN_PASSWORD || "admin@jurislpo";
  await setPassword(codeToId[SUPER_ADMIN.code], adminPw, true);

  for (const [name, description] of [["Human Resources", "People operations, hiring and policies"], ["Finance", "Payroll, tax and accounts"]]) {
    const row = await db.department.upsert({ where: { name }, create: { name, description, createdById: codeToId[SUPER_ADMIN.code] }, update: {} });
    deptIds[name] = row.id;
  }

  const demo = (process.env.SEED_DEMO ?? "true") !== "false";
  if (demo) {
    // Sana (Admin) needs to exist before she can be "creator" of the core departments in the demo story.
    codeToId["JC-002"] = await upsertEmployee(PEOPLE[0], roleIds, shiftIds, deptIds, codeToId, codeToId[SUPER_ADMIN.code]);
    for (const p of PEOPLE.slice(1)) {
      for (const dep of [p.dept].filter(Boolean) as string[]) {
        if (!deptIds[dep]) {
          const row = await db.department.upsert({ where: { name: dep }, create: { name: dep, createdById: codeToId["JC-012"] ?? codeToId["JC-002"] }, update: {} });
          deptIds[dep] = row.id;
        }
      }
    }
    // two passes so managers resolve regardless of order
    for (const pass of [1, 2]) {
      for (const p of PEOPLE.slice(1)) {
        const creator = p.role === "fe" ? codeToId["JC-004"] : ["hrm", "fm"].includes(p.role) ? codeToId["JC-002"] : codeToId["JC-012"];
        codeToId[p.code] = await upsertEmployee(p, roleIds, shiftIds, deptIds, codeToId, creator ?? codeToId["JC-002"]);
      }
      if (pass === 1) {
        // departments created by Riya once she exists
        for (const name of ["Technology", "Litigation Support", "Contract Management", "Document Review", "Client Services"]) {
          await db.department.update({ where: { name }, data: { createdById: codeToId["JC-012"] } });
        }
      }
    }
    const heads: [string, string][] = [["Human Resources", "JC-012"], ["Finance", "JC-004"], ["Technology", "JC-035"], ["Litigation Support", "JC-035"], ["Client Services", "JC-091"]];
    for (const [dep, code] of heads) await db.department.update({ where: { name: dep }, data: { headEmployeeId: codeToId[code] } });

    const demoPw = process.env.SEED_DEMO_PASSWORD || undefined;
    for (const p of PEOPLE) await setPassword(codeToId[p.code], demoPw);

    // 2026 holidays (prototype calendar), added by the HR Manager
    const HOL: [string, string, HolidayRegion][] = [
      ["2026-01-26", "Republic Day", "IN"], ["2026-03-04", "Holi", "IN"], ["2026-08-15", "Independence Day", "IN"], ["2026-09-07", "Labor Day", "US"],
      ["2026-09-14", "Ganesh Chaturthi", "IN"], ["2026-10-02", "Gandhi Jayanti", "IN"], ["2026-10-20", "Dussehra", "IN"], ["2026-11-09", "Govardhan Puja", "IN"],
      ["2026-11-24", "Guru Nanak Jayanti", "IN"], ["2026-11-26", "Thanksgiving Day", "US"], ["2026-12-25", "Christmas", "ALL"],
      ["2026-01-01", "New Year's Day", "US"], ["2026-05-25", "Memorial Day", "US"], ["2026-07-03", "Independence Day (observed)", "US"],
    ];
    for (const [date, name, region] of HOL) {
      await db.holiday.upsert({ where: { date_region: { date: d(date), region } }, create: { date: d(date), name, region, createdById: codeToId["JC-012"] }, update: {} });
    }

    // Salary structures (monthly, ₹ → paise). Effective-dated; history only grows.
    const ST: [string, string, number, number, number, number, StructureReason][] = [
      ["JC-002", "2026-04-01", 55000, 22000, 5500, 27500, "APPRAISAL"],
      ["JC-004", "2025-04-01", 55000, 22000, 5500, 27500, "APPRAISAL"], ["JC-004", "2026-04-01", 60000, 24000, 6000, 30000, "APPRAISAL"],
      ["JC-012", "2025-08-04", 47500, 19000, 4750, 23750, "JOINING"],
      ["JC-015", "2026-01-12", 26000, 10400, 2600, 13000, "JOINING"],
      ["JC-018", "2025-11-03", 30000, 12000, 3000, 15000, "JOINING"],
      ["JC-030", "2023-02-01", 28000, 11200, 2800, 14000, "JOINING"],
      ["JC-035", "2025-04-01", 82500, 33000, 8250, 41250, "APPRAISAL"], ["JC-035", "2026-04-01", 92500, 37000, 9250, 46250, "APPRAISAL"],
      ["JC-049", "2024-11-18", 31000, 12400, 3100, 15500, "JOINING"],
      ["JC-058", "2023-06-05", 36000, 14400, 3600, 18000, "JOINING"],
      ["JC-072", "2025-01-20", 29000, 11600, 2900, 14500, "JOINING"],
      ["JC-074", "2026-09-08", 75000, 30000, 7500, 37500, "JOINING"],
      ["JC-081", "2026-03-02", 24000, 9600, 2400, 12000, "JOINING"],
    ];
    for (const [code, from, basic, hra, lta, special, reason] of ST) {
      await db.salaryStructure.upsert({
        where: { employeeId_effectiveFrom: { employeeId: codeToId[code], effectiveFrom: d(from) } },
        create: { employeeId: codeToId[code], effectiveFrom: d(from), basic: paise(basic), hra: paise(hra), lta: paise(lta), special: paise(special), reason, createdById: codeToId["JC-004"] },
        update: {},
      });
    }

    // Tax profiles + old-regime declarations (prototype)
    const fy = "2026-27";
    await db.taxProfile.upsert({ where: { employeeId_fy: { employeeId: codeToId["JC-074"], fy } }, create: { employeeId: codeToId["JC-074"], fy, regime: "NEW", metro: true, prevEmployerIncome: BigInt(paise(750000)), prevEmployerTds: BigInt(paise(38000)) }, update: {} });
    const DECL: [string, Record<string, [string, number]>, "PENDING" | "APPROVED"][] = [
      ["JC-058", { rent: ["HRA", 180000], ppf: ["80C", 80000], lic: ["80C", 25000], medSelf: ["80D_SELF", 18000] }, "PENDING"],
      ["JC-049", { rent: ["HRA", 144000], elss: ["80C", 60000], medParents: ["80D_PARENTS", 30000] }, "APPROVED"],
    ];
    for (const [code, items, status] of DECL) {
      const employeeId = codeToId[code];
      await db.taxProfile.upsert({ where: { employeeId_fy: { employeeId, fy } }, create: { employeeId, fy, regime: "OLD", metro: true }, update: {} });
      for (const [itemKey, [section, amt]] of Object.entries(items)) {
        await db.declarationItem.upsert({
          where: { employeeId_fy_itemKey: { employeeId, fy, itemKey } },
          create: { employeeId, fy, section, itemKey, amount: BigInt(paise(amt)), proofName: `${itemKey}-proof.pdf`, status, decidedById: status === "APPROVED" ? codeToId["JC-004"] : null, decidedAt: status === "APPROVED" ? new Date() : null },
          update: {},
        });
      }
    }
  }

  const counts = await Promise.all([db.employee.count(), db.role.count(), db.department.count(), db.holiday.count(), db.salaryStructure.count()]);
  console.log(`Seed complete — employees ${counts[0]}, roles ${counts[1]}, departments ${counts[2]}, holidays ${counts[3]}, structures ${counts[4]}`);
  console.log(`Super admin: ${ADMIN_EMAIL}${demo ? ` · demo password for JC-002…JC-091: ${process.env.SEED_DEMO_PASSWORD ? "(SEED_DEMO_PASSWORD)" : "none — use invite links"}` : ""}`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => db.$disconnect());
