"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { loadActor, loadTarget } from "@/lib/actor";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { sendInvite } from "@/lib/auth-tokens";
import { decryptPII, encryptPII, last4 } from "@/lib/crypto";
import { db } from "@/lib/db";
import { cleanAadhaar, cleanUan, employeeInput, stepErrors, type EmployeeInput } from "@/lib/employee-schema";
import { dateToIso, isoToDate, todayIn } from "@/lib/format";
import { maskAadhaar, maskPan, maskPhone, maskUan } from "@/lib/mask";
import { canAddEmployees, canAssignRole, canEditEmployee, canToggleAccess, createsCycle, initialStatus, isAdmin, LOCATIONS, locationOfTz, lockedDepartment } from "@/lib/people-rules";
import { requestMeta } from "@/lib/request";
import { revokeAllSessions } from "@/lib/session";
import { shiftLabel } from "@/lib/shifts";

const uuid = z.string().uuid();
const IST = "Asia/Kolkata";
const ip = async () => (await requestMeta()).ip;

const maskAddress = (a: string | null) => {
  if (!a) return "—";
  const parts = a.split(",").map((s) => s.trim()).filter(Boolean);
  return parts.length > 1 ? `•••••, ${parts[parts.length - 1]}` : "On file";
};

const refresh = () => {
  revalidatePath("/people");
  revalidatePath("/directory");
  revalidatePath("/team");
  revalidatePath("/departments");
};

// ───────────────────────── profile drawer ─────────────────────────

export interface Profile {
  id: string;
  code: string;
  name: string;
  father: string;
  email: string;
  phone: string;
  address: string;
  pan: string;
  aadhaar: string;
  uan: string;
  designation: string;
  department: string;
  manager: string;
  reports: string[];
  doj: string;
  exit: string | null;
  location: string;
  india: boolean;
  shift: string;
  role: string;
  status: string;
  access: boolean;
  invitePending: boolean;
  gross: number | null; // paise, only for struct.manage
  canEdit: boolean;
  canToggle: boolean;
}

export async function getProfile(id: string): Promise<ActionResult<Profile>> {
  return run(async () => {
    const u = await assertPerm("people.view");
    uuid.parse(id);
    const e = await db.employee.findUnique({
      where: { id },
      include: {
        department: true, reportingManager: { select: { fullName: true } }, role: true, shift: true, user: true,
        directReports: { where: { status: { not: "EXITED" } }, select: { fullName: true }, orderBy: { fullName: "asc" } },
      },
    });
    if (!e) throw new UserError("That employee no longer exists.");
    const actor = await loadActor(u);
    const target = { id: e.id, roleId: e.roleId, roleKey: e.role.key, status: e.status, isSuperAdmin: !!e.user?.isSuperAdmin };

    // Salary is fetched only for struct.manage — HR never receives it.
    let gross: number | null = null;
    if (u.perms.has("struct.manage")) {
      const st = await db.salaryStructure.findFirst({ where: { employeeId: id, effectiveFrom: { lte: isoToDate(todayIn(IST)) } }, orderBy: { effectiveFrom: "desc" } });
      gross = st ? st.basic + st.hra + st.lta + st.special : 0;
    }
    const india = e.region === "IN";
    return {
      ok: true,
      data: {
        id: e.id, code: e.code, name: e.fullName, father: e.fatherName ?? "—", email: e.email,
        phone: maskPhone(e.phoneLast4), address: maskAddress(decryptPII(e.addressEnc)),
        pan: india ? maskPan(e.panLast4) : "—", aadhaar: india ? maskAadhaar(e.aadhaarLast4) : "—", uan: india ? maskUan(e.uanLast4) : "—",
        designation: e.designation, department: e.department?.name ?? "—", manager: e.reportingManager?.fullName ?? "—",
        reports: e.directReports.map((r) => r.fullName), doj: dateToIso(e.dateOfJoining), exit: e.exitDate ? dateToIso(e.exitDate) : null,
        location: locationOfTz(e.timeZone).l, india, shift: shiftLabel(e.shift), role: e.role.name, status: e.status, access: e.portalAccess,
        invitePending: !e.user?.passwordHash, gross,
        canEdit: canEditEmployee(actor, target) && e.status !== "EXITED",
        canToggle: canToggleAccess(actor, target),
      },
    };
  });
}

/** Full phone + address for people who may edit the record. Every reveal is audited. */
export async function revealContact(id: string): Promise<ActionResult<{ phone: string; address: string }>> {
  return run(async () => {
    const u = await assertPerm("people.add");
    uuid.parse(id);
    const [actor, target] = await Promise.all([loadActor(u), loadTarget(id)]);
    if (!target || !canEditEmployee(actor, target)) throw new UserError("You can't view this person's contact details.");
    const e = await db.employee.findUniqueOrThrow({ where: { id }, select: { phoneEnc: true, addressEnc: true } });
    await audit({ actorId: u.id, action: "employee.pii_reveal", entity: "employee", entityId: id, after: { fields: ["phone", "address"] }, ip: await ip() });
    return { ok: true, data: { phone: decryptPII(e.phoneEnc) ?? "—", address: decryptPII(e.addressEnc) ?? "—" } };
  });
}

// ───────────────────────── access & exit ─────────────────────────

export async function toggleAccess(id: string, on: boolean): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("access.toggle");
    uuid.parse(id);
    const [actor, target] = await Promise.all([loadActor(u), loadTarget(id)]);
    if (!target || !canToggleAccess(actor, target)) throw new UserError("You can't change portal access for this person.");
    const e = await db.employee.findUniqueOrThrow({ where: { id }, select: { fullName: true, portalAccess: true } });
    const address = await ip();
    await db.$transaction(async (tx) => {
      await tx.employee.update({ where: { id }, data: { portalAccess: on } });
      if (!on) await revokeAllSessions(id, "access_disabled", tx);
      await audit({ actorId: u.id, action: "employee.access_toggle", entity: "employee", entityId: id, before: { portalAccess: e.portalAccess }, after: { portalAccess: on }, ip: address }, tx);
    });
    refresh();
    return { ok: true, msg: on ? `Portal access enabled for ${e.fullName}` : `${e.fullName} can no longer sign in` };
  });
}

export async function markExited(id: string, exitDate: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("people.add");
    uuid.parse(id);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(exitDate)) throw new UserError("Pick the last working day.");
    const [actor, target] = await Promise.all([loadActor(u), loadTarget(id)]);
    if (!target || !canEditEmployee(actor, target)) throw new UserError("You can't change this person's record.");
    if (target.status === "EXITED") throw new UserError("Already a past employee.");
    const e = await db.employee.findUniqueOrThrow({
      where: { id },
      select: { fullName: true, dateOfJoining: true, status: true, portalAccess: true, _count: { select: { directReports: { where: { status: { not: "EXITED" } } } } } },
    });
    if (exitDate < dateToIso(e.dateOfJoining)) throw new UserError("The last working day can't be before the joining date.");
    if (e._count.directReports) throw new UserError(`${e.fullName} still has ${e._count.directReports} direct report${e._count.directReports === 1 ? "" : "s"}. Assign them a new manager first.`);
    const address = await ip();
    await db.$transaction(async (tx) => {
      await tx.employee.update({ where: { id }, data: { status: "EXITED", exitDate: isoToDate(exitDate), portalAccess: false } });
      await tx.department.updateMany({ where: { headEmployeeId: id }, data: { headEmployeeId: null } });
      await revokeAllSessions(id, "exited", tx);
      await audit({ actorId: u.id, action: "employee.exit", entity: "employee", entityId: id, before: { status: e.status, portalAccess: e.portalAccess }, after: { status: "EXITED", exitDate, portalAccess: false }, ip: address }, tx);
    });
    refresh();
    return { ok: true, msg: `${e.fullName} marked as a past employee · access removed` };
  });
}

export async function resendInvite(id: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("people.add");
    uuid.parse(id);
    const [actor, target] = await Promise.all([loadActor(u), loadTarget(id)]);
    if (!target || !canEditEmployee(actor, target) || target.status === "EXITED") throw new UserError("You can't send an invite for this person.");
    const user = await db.user.findUnique({ where: { employeeId: id } });
    if (user?.passwordHash) throw new UserError("This person has already set a password.");
    await sendInvite(id);
    await audit({ actorId: u.id, action: "employee.invite_sent", entity: "employee", entityId: id, ip: await ip() });
    return { ok: true, msg: "Invite sent to their official email" };
  });
}

// ───────────────────────── add / edit ─────────────────────────

export interface FormOptions {
  nextCode: string;
  departments: { id: string; name: string; headId: string | null }[];
  lockedDepartmentId: string | null;
  managers: { id: string; name: string; title: string }[];
  roles: { id: string; name: string; perms: number }[];
  defaults: EmployeeInput;
  editing: { code: string; name: string; last4: { phone: string | null; pan: string | null; aadhaar: string | null; uan: string | null }; hasAddress: boolean; hadManager: boolean } | null;
}

async function nextCode() {
  const rows = await db.$queryRaw<{ n: number | null }[]>`SELECT max(substring(code from 4)::int) AS n FROM employees WHERE code ~ '^JC-[0-9]+$'`;
  return `JC-${String((rows[0]?.n ?? 0) + 1).padStart(3, "0")}`;
}

export async function getFormOptions(editId?: string): Promise<ActionResult<FormOptions>> {
  return run(async () => {
    const u = await assertPerm("people.add");
    const actor = await loadActor(u);
    let current: Awaited<ReturnType<typeof db.employee.findUnique>> = null;
    if (editId) {
      uuid.parse(editId);
      const target = await loadTarget(editId);
      if (!target || !canEditEmployee(actor, target) || target.status === "EXITED") throw new UserError("You can't edit this person.");
      current = await db.employee.findUnique({ where: { id: editId } });
    } else if (!canAddEmployees(actor)) throw new UserError("You can't add employees.");

    const locked = lockedDepartment(actor);
    const [departments, managers, roles, shifts] = await Promise.all([
      db.department.findMany({ where: { archivedAt: null, ...(locked ? { id: locked } : {}) }, orderBy: { name: "asc" }, select: { id: true, name: true, headEmployeeId: true } }),
      db.employee.findMany({ where: { status: { not: "EXITED" }, ...(editId ? { id: { not: editId } } : {}) }, orderBy: { fullName: "asc" }, select: { id: true, fullName: true, designation: true, department: { select: { name: true } } } }),
      db.role.findMany({ include: { _count: { select: { permissions: true } } }, orderBy: { createdAt: "asc" } }),
      db.shift.findMany(),
    ]);
    const allowedRoles = roles.filter((r) => canAssignRole(actor, r.id, current?.roleId) || (isAdmin(actor) && r.key !== "admin") || r.id === current?.roleId);
    const employeeRole = roles.find((r) => r.key === "employee");
    const shiftCode = (sid: string | null) => shifts.find((s) => s.id === sid)?.code as EmployeeInput["shift"] | undefined;

    const defaults: EmployeeInput = current
      ? {
          fullName: current.fullName, fatherName: current.fatherName ?? "", email: current.email, phone: "", location: locationOfTz(current.timeZone).v,
          pan: "", aadhaar: "", uan: "", address: "", designation: current.designation, departmentId: current.departmentId ?? "",
          managerId: current.reportingManagerId ?? "", doj: dateToIso(current.dateOfJoining), shift: shiftCode(current.shiftId) ?? (current.region === "US" ? "US_DAY" : "IN_NIGHT"), roleId: current.roleId,
        }
      : (() => {
          const dept = locked ? departments[0] : departments.find((d) => !["Human Resources", "Finance"].includes(d.name)) ?? departments[0];
          const role = allowedRoles.find((r) => r.id === employeeRole?.id) ?? allowedRoles[0];
          return {
            fullName: "", fatherName: "", email: "", phone: "", location: "IN_IST", pan: "", aadhaar: "", uan: "", address: "", designation: "",
            departmentId: dept?.id ?? "", managerId: (locked ? u.id : dept?.headEmployeeId) ?? "", doj: todayIn(IST), shift: "IN_NIGHT", roleId: role?.id ?? "",
          };
        })();

    return {
      ok: true,
      data: {
        nextCode: current ? current.code : await nextCode(),
        departments: departments.map((d) => ({ id: d.id, name: d.name, headId: d.headEmployeeId })),
        lockedDepartmentId: locked,
        managers: managers.map((m) => ({ id: m.id, name: m.fullName, title: `${m.designation}${m.department ? " · " + m.department.name : ""}` })),
        roles: allowedRoles.map((r) => ({ id: r.id, name: r.name, perms: r._count.permissions })),
        defaults,
        editing: current
          ? { code: current.code, name: current.fullName, last4: { phone: current.phoneLast4, pan: current.panLast4, aadhaar: current.aadhaarLast4, uan: current.uanLast4 }, hasAddress: !!current.addressEnc, hadManager: !!current.reportingManagerId }
          : null,
      },
    };
  });
}

export async function saveEmployee(input: EmployeeInput, editId?: string): Promise<ActionResult<{ id: string; code: string }>> {
  return run(async () => {
    const u = await assertPerm("people.add");
    const f = employeeInput.parse(input);
    const actor = await loadActor(u);
    const editing = !!editId;
    let current: Awaited<ReturnType<typeof db.employee.findUniqueOrThrow>> | null = null;
    if (editId) {
      uuid.parse(editId);
      const target = await loadTarget(editId);
      if (!target || !canEditEmployee(actor, target) || target.status === "EXITED") throw new UserError("You can't edit this person.");
      current = await db.employee.findUniqueOrThrow({ where: { id: editId } });
    } else if (!canAddEmployees(actor)) throw new UserError("You can't add employees.");

    for (const step of [1, 2, 3] as const) {
      const errs = stepErrors(step, f, editing, !!current?.reportingManagerId);
      const first = Object.values(errs)[0];
      if (first) throw new UserError(first);
    }

    // Role: only from the creator's assignable list (or unchanged on edit).
    const role = await db.role.findUnique({ where: { id: f.roleId } });
    if (!role) throw new UserError("Pick a role.");
    const roleAllowed = canAssignRole(actor, f.roleId, current?.roleId) || (isAdmin(actor) && role.key !== "admin");
    if (!roleAllowed) throw new UserError(`You can't give people the ${role.name} role.`);

    // Department: must exist; locked to the actor's own department where the rule applies.
    const dept = await db.department.findUnique({ where: { id: f.departmentId } });
    if (!dept || dept.archivedAt) throw new UserError("Pick a department.");
    const locked = lockedDepartment(actor);
    if (locked && f.departmentId !== locked) throw new UserError("You can add people to your own department only.");

    // Manager: current employee, not themselves, no reporting loops.
    if (f.managerId) {
      const m = await db.employee.findUnique({ where: { id: f.managerId }, select: { status: true } });
      if (!m || m.status === "EXITED") throw new UserError("Pick a current employee as reporting manager.");
      if (editId) {
        if (f.managerId === editId) throw new UserError("Someone can't report to themselves.");
        const all = await db.employee.findMany({ select: { id: true, reportingManagerId: true } });
        if (createsCycle(editId, f.managerId, new Map(all.map((e) => [e.id, e.reportingManagerId])))) throw new UserError("That manager already reports (directly or indirectly) to this person.");
      }
    }

    const email = f.email.toLowerCase();
    const clash = await db.employee.findFirst({ where: { email, ...(editId ? { id: { not: editId } } : {}) }, select: { code: true } });
    if (clash) throw new UserError(`${clash.code} already uses that email.`);

    const loc = LOCATIONS.find((l) => l.v === f.location)!;
    const india = loc.region === "IN";
    const shiftCode = india ? (f.shift === "US_DAY" ? "IN_NIGHT" : f.shift) : "US_DAY";
    const shift = await db.shift.findUniqueOrThrow({ where: { code: shiftCode } });

    // PII: on edit, blank means keep; switching to the US clears the Indian IDs.
    const pii: Record<string, string | null> = {};
    const setPII = (field: "phone" | "pan" | "aadhaar" | "uan", value: string) => {
      pii[`${field}Enc`] = encryptPII(value);
      pii[`${field}Last4`] = last4(value);
    };
    if (f.phone) setPII("phone", f.phone);
    if (f.address) pii.addressEnc = encryptPII(f.address);
    if (india) {
      if (f.pan) setPII("pan", f.pan.toUpperCase());
      if (f.aadhaar) setPII("aadhaar", cleanAadhaar(f.aadhaar));
      if (f.uan) setPII("uan", cleanUan(f.uan));
    } else {
      for (const k of ["pan", "aadhaar", "uan"]) { pii[`${k}Enc`] = null; pii[`${k}Last4`] = null; }
    }

    const data = {
      fullName: f.fullName, fatherName: f.fatherName, email, designation: f.designation, departmentId: f.departmentId,
      reportingManagerId: f.managerId || null, roleId: f.roleId, dateOfJoining: isoToDate(f.doj), timeZone: loc.tz, region: loc.region,
      shiftId: shift.id, ...pii,
    };
    const address = await ip();
    const changedPII = Object.keys(pii).filter((k) => k.endsWith("Enc")).map((k) => k.replace("Enc", ""));

    if (current) {
      const before = { fullName: current.fullName, fatherName: current.fatherName, email: current.email, designation: current.designation, departmentId: current.departmentId, reportingManagerId: current.reportingManagerId, roleId: current.roleId, dateOfJoining: dateToIso(current.dateOfJoining), timeZone: current.timeZone, shiftId: current.shiftId };
      const after = { fullName: data.fullName, fatherName: data.fatherName, email, designation: data.designation, departmentId: data.departmentId, reportingManagerId: data.reportingManagerId, roleId: data.roleId, dateOfJoining: f.doj, timeZone: data.timeZone, shiftId: data.shiftId };
      const doj = f.doj;
      const status = current.status === "ONBOARDING" || current.status === "PROBATION" ? initialStatus(doj, todayIn(IST)) : current.status;
      await db.$transaction(async (tx) => {
        await tx.employee.update({ where: { id: current.id }, data: { ...data, status } });
        await audit({ actorId: u.id, action: "employee.edit", entity: "employee", entityId: current.id, before, after: { ...after, piiChanged: changedPII }, ip: address }, tx);
      });
      refresh();
      return { ok: true, msg: `Saved changes for ${f.fullName}`, data: { id: current.id, code: current.code } };
    }

    const created = await db.$transaction(async (tx) => {
      // serialise code allocation
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(74201)`;
      const rows = await tx.$queryRaw<{ n: number | null }[]>`SELECT max(substring(code from 4)::int) AS n FROM employees WHERE code ~ '^JC-[0-9]+$'`;
      const code = `JC-${String((rows[0]?.n ?? 0) + 1).padStart(3, "0")}`;
      const e = await tx.employee.create({ data: { ...data, code, status: initialStatus(f.doj, todayIn(IST)), portalAccess: true, createdById: u.id } });
      await tx.user.create({ data: { employeeId: e.id } });
      await audit({ actorId: u.id, action: "employee.create", entity: "employee", entityId: e.id, after: { code, ...data, phoneEnc: undefined, addressEnc: undefined, panEnc: undefined, aadhaarEnc: undefined, uanEnc: undefined, piiProvided: changedPII }, ip: address }, tx);
      if (india) {
        // Finance is told a salary structure is needed (REQUIREMENTS §5.2).
        const finance = await tx.employee.findMany({
          where: { status: { not: "EXITED" }, portalAccess: true, role: { permissions: { some: { permissionKey: "struct.manage" } } } },
          select: { id: true },
        });
        if (finance.length) await tx.notification.createMany({ data: finance.map((x) => ({ employeeId: x.id, text: `${f.fullName} (${code}) joined — salary structure pending`, link: "/structures" })) });
      }
      return e;
    });
    await sendInvite(created.id);
    refresh();
    return { ok: true, msg: `${f.fullName} added as ${created.code} · ${role.name}${india ? " · salary structure pending with Finance" : ""} · invite sent`, data: { id: created.id, code: created.code } };
  });
}
