import "server-only";
import nodemailer from "nodemailer";
import { db } from "./db";
import { env } from "./env";

export interface Mail {
  to: string;
  subject: string;
  text: string;
  html?: string;
}

/**
 * Every email is recorded in `email_outbox`. With SMTP configured it is sent; without it (dev)
 * it is logged to the server console so invite / reset links can be copied.
 */
export async function sendEmail(mail: Mail) {
  const e = env();
  const row = await db.emailOutbox.create({ data: { to: mail.to, subject: mail.subject, text: mail.text, html: mail.html } });
  if (!e.SMTP_HOST) {
    console.info(`\n✉  [email:dev] to=${mail.to}\n   subject=${mail.subject}\n   ${mail.text.replace(/\n/g, "\n   ")}\n`);
    await db.emailOutbox.update({ where: { id: row.id }, data: { status: "LOGGED", sentAt: new Date() } });
    return;
  }
  try {
    const t = nodemailer.createTransport({
      host: e.SMTP_HOST,
      port: e.SMTP_PORT,
      secure: e.SMTP_PORT === 465,
      auth: e.SMTP_USER ? { user: e.SMTP_USER, pass: e.SMTP_PASS } : undefined,
    });
    await t.sendMail({ from: e.MAIL_FROM, to: mail.to, subject: mail.subject, text: mail.text, html: mail.html });
    await db.emailOutbox.update({ where: { id: row.id }, data: { status: "SENT", sentAt: new Date() } });
  } catch (err) {
    await db.emailOutbox.update({ where: { id: row.id }, data: { status: "FAILED", error: String(err).slice(0, 1000) } });
    console.error("[email] send failed", err);
  }
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

const shell = (title: string, body: string) => `<!doctype html><html><body style="margin:0;background:#F6F6F8;font-family:Arial,sans-serif;color:#121216">
<div style="max-width:520px;margin:32px auto;background:#fff;border:1px solid #E7E7EC;border-radius:16px;padding:28px">
<div style="font-weight:700;font-size:15px;margin-bottom:18px"><span style="display:inline-block;width:28px;height:28px;border-radius:8px;background:#6E56CF;color:#fff;text-align:center;line-height:28px;margin-right:8px">J</span>JurisOne</div>
<h2 style="margin:0 0 12px;font-size:22px">${title}</h2>${body}
<p style="color:#8A8C99;font-size:12px;margin-top:28px">Juris Consultants · this is an automated message.</p></div></body></html>`;

const button = (href: string, label: string) =>
  `<p style="margin:22px 0"><a href="${href}" style="background:#6E56CF;color:#fff;text-decoration:none;padding:12px 18px;border-radius:10px;font-weight:600">${label}</a></p><p style="color:#5D5F6D;font-size:12px;word-break:break-all">Or paste this link: ${href}</p>`;

export function inviteEmail(name: string, code: string, link: string, hours: number): Omit<Mail, "to"> {
  return {
    subject: "Welcome to JurisOne — set your password",
    text: `Hi ${name},\n\nYour JurisOne account (${code}) is ready. Set your password with this one-time link (valid ${hours} hours):\n${link}\n\nSign in afterwards with your employee ID or official email.`,
    html: shell(`Welcome, ${esc(name)}`, `<p>Your JurisOne account <b>${code}</b> is ready. Set your password with this one-time link — it expires in ${hours} hours.</p>${button(link, "Set my password")}`),
  };
}

export function decisionEmail(name: string, what: string, approved: boolean, by: string, note: string | null): Omit<Mail, "to"> {
  const verb = approved ? "approved" : "declined";
  return {
    subject: `${what} ${verb}`,
    text: `Hi ${name},\n\nYour ${what.toLowerCase()} was ${verb} by ${by}.${note ? `\n\nNote: ${note}` : ""}\n\nOpen JurisOne for details.`,
    html: shell(`${esc(what)} ${verb}`, `<p>Hi ${esc(name)}, your ${esc(what.toLowerCase())} was <b>${verb}</b> by ${esc(by)}.</p>${note ? `<p style="color:#5D5F6D">Note: ${esc(note)}</p>` : ""}`),
  };
}

export function resetEmail(name: string, link: string, minutes: number): Omit<Mail, "to"> {
  return {
    subject: "Reset your JurisOne password",
    text: `Hi ${name},\n\nUse this one-time link to choose a new password (valid ${minutes} minutes):\n${link}\n\nIf you didn't ask for this, you can ignore this email.`,
    html: shell("Reset your password", `<p>Hi ${esc(name)}, use this one-time link to choose a new password. It expires in ${minutes} minutes.</p>${button(link, "Choose a new password")}<p style="color:#5D5F6D;font-size:13px">If you didn't ask for this, ignore this email — your password stays the same.</p>`),
  };
}
