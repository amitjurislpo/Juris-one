"use client";

import { useState, useTransition } from "react";
import { useToast } from "@/components/shell/Toasts";
import { PhoneInput } from "@/components/ui/PhoneInput";
import { phoneProblem } from "@/lib/phone";
import { saveMyProfile } from "./actions";

export interface MyProfile {
  code: string; fullName: string; firstName: string; middleName: string; lastName: string; email: string; phone: string | null; designation: string;
  department: string | null; manager: string | null; role: string; dateOfJoining: string; joiningOffice: string | null; location: string; shift: string | null;
  pan: string | null; aadhaar: string | null; bank: string | null;
  fatherName: string; dateOfBirth: string; currentAddress: string; permanentAddress: string; emergencyName: string; emergencyRelation: string;
  emergencyPhoneCountry: string; emergencyPhone: string;
  documents: { id: string; kind: string; label: string; fileName: string }[];
}

/** The details an employee edits themselves. */
export function ProfileForm({ p }: { p: MyProfile }) {
  const toast = useToast();
  const init = { fatherName: p.fatherName, dateOfBirth: p.dateOfBirth, currentAddress: p.currentAddress, permanentAddress: p.permanentAddress,
    emergencyName: p.emergencyName, emergencyRelation: p.emergencyRelation, emergencyPhoneCountry: p.emergencyPhoneCountry || "+91", emergencyPhone: p.emergencyPhone };
  const [f, setF] = useState(init);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const on = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => { setF({ ...f, [k]: e.target.value }); setErr(""); };
  const dirty = JSON.stringify(f) !== JSON.stringify(init);
  const save = () => {
    if (f.fatherName.trim().length < 2) return setErr("Enter your father's name.");
    if (f.emergencyPhone) { const pp = phoneProblem(f.emergencyPhoneCountry, f.emergencyPhone); if (pp) return setErr(`Emergency contact: ${pp}`); }
    start(async () => {
      const r = await saveMyProfile(f);
      if (r.ok) toast(r.msg!); else setErr(r.error);
    });
  };
  return (
    <section className="card" style={{ padding: 18 }}>
      <div className="card-h"><h3>Personal details</h3><small className="muted">You can edit these</small></div>
      <div className="col" style={{ gap: 12 }}>
        <div className="grid2">
          <label className="field">Father&apos;s name *<input value={f.fatherName} onChange={on("fatherName")} maxLength={120} /></label>
          <label className="field">Date of birth<input type="date" value={f.dateOfBirth} onChange={on("dateOfBirth")} /></label>
        </div>
        <label className="field">Current address<textarea value={f.currentAddress} onChange={on("currentAddress")} rows={2} maxLength={500} /></label>
        <label className="field">Permanent address<textarea value={f.permanentAddress} onChange={on("permanentAddress")} rows={2} maxLength={500} /></label>
        <div className="grid2">
          <label className="field">Emergency contact<input value={f.emergencyName} onChange={on("emergencyName")} maxLength={120} /></label>
          <label className="field">Relationship<input value={f.emergencyRelation} onChange={on("emergencyRelation")} maxLength={40} /></label>
        </div>
        <div className="field">Their phone
          <PhoneInput code={f.emergencyPhoneCountry} number={f.emergencyPhone} onChange={(c, n) => { setF({ ...f, emergencyPhoneCountry: c, emergencyPhone: n }); setErr(""); }} />
        </div>
        {err && <div className="err" role="alert">{err}</div>}
        <div><button className="btn btn-gold" disabled={busy || !dirty} onClick={save}>{busy ? "Saving…" : "Save"}</button></div>
      </div>
    </section>
  );
}
