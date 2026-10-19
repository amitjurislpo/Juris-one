package com.jurislpo.jurisone.service;

/** Email bodies, verbatim from src/lib/email.ts. */
public final class MailTemplates {
    private MailTemplates() {}

    public record Content(String subject, String text, String html) {}

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    static String shell(String title, String body) {
        return "<!doctype html><html><body style=\"margin:0;background:#F6F6F8;font-family:Arial,sans-serif;color:#121216\">\n"
                + "<div style=\"max-width:520px;margin:32px auto;background:#fff;border:1px solid #E7E7EC;border-radius:16px;padding:28px\">\n"
                + "<div style=\"font-weight:700;font-size:15px;margin-bottom:18px\"><span style=\"display:inline-block;width:28px;height:28px;border-radius:8px;background:#6E56CF;color:#fff;text-align:center;line-height:28px;margin-right:8px\">J</span>JurisOne</div>\n"
                + "<h2 style=\"margin:0 0 12px;font-size:22px\">" + title + "</h2>" + body + "\n"
                + "<p style=\"color:#8A8C99;font-size:12px;margin-top:28px\">Juris Consultants · this is an automated message.</p></div></body></html>";
    }

    static String button(String href, String label) {
        return "<p style=\"margin:22px 0\"><a href=\"" + href + "\" style=\"background:#6E56CF;color:#fff;text-decoration:none;padding:12px 18px;border-radius:10px;font-weight:600\">"
                + label + "</a></p><p style=\"color:#5D5F6D;font-size:12px;word-break:break-all\">Or paste this link: " + href + "</p>";
    }

    public static Content invite(String name, String code, String link, int hours) {
        return new Content(
                "Welcome to JurisOne — set your password",
                "Hi " + name + ",\n\nYour JurisOne account (" + code + ") is ready. Set your password with this one-time link (valid " + hours + " hours):\n"
                        + link + "\n\nSign in afterwards with your official email.",
                shell("Welcome, " + esc(name), "<p>Your JurisOne account <b>" + code + "</b> is ready. Set your password with this one-time link — it expires in "
                        + hours + " hours.</p>" + button(link, "Set my password")));
    }

    /** The candidate's onboarding link (to their personal email); {@code note}: what HR asked to change, if sent back. */
    public static Content onboarding(String name, String business, String designation, String link, String expires, String note) {
        boolean back = note != null && !note.isEmpty();
        String role = designation == null || designation.isEmpty() ? "" : " as " + designation;
        String subject = back ? business + " needs a few changes to your onboarding details" : "Welcome to " + business + " — complete your onboarding";
        String intro = back ? "HR at " + business + " reviewed your details and asked for a few changes."
                : business + " is looking forward to you joining" + role + ". Please fill in your details and upload your documents.";
        return new Content(subject,
                "Hi " + name + ",\n\n" + intro + (back ? "\n\nWhat to change: " + note : "") + "\n\nOpen your personal link (valid until " + expires + "):\n" + link
                        + "\n\nKeep this link to yourself — anyone with it can see what you entered.",
                shell(esc(subject), "<p>Hi " + esc(name) + ", " + esc(intro) + "</p>"
                        + (back ? "<p style=\"color:#5D5F6D\">What to change: " + esc(note) + "</p>" : "")
                        + button(link, back ? "Update my details" : "Start onboarding")
                        + "<p style=\"color:#5D5F6D;font-size:12px\">The link works until " + esc(expires) + ". Keep it to yourself — anyone with it can see what you entered.</p>"));
    }

    public static Content decision(String name, String what, boolean approved, String by, String note) {
        String verb = approved ? "approved" : "declined";
        boolean hasNote = note != null && !note.isEmpty();
        return new Content(
                what + " " + verb,
                "Hi " + name + ",\n\nYour " + what.toLowerCase() + " was " + verb + " by " + by + "." + (hasNote ? "\n\nNote: " + note : "")
                        + "\n\nOpen JurisOne for details.",
                shell(esc(what) + " " + verb, "<p>Hi " + esc(name) + ", your " + esc(what.toLowerCase()) + " was <b>" + verb + "</b> by " + esc(by) + ".</p>"
                        + (hasNote ? "<p style=\"color:#5D5F6D\">Note: " + esc(note) + "</p>" : "")));
    }

    /** Subscription ending soon ({@code daysLeft} > 0) or ended ({@code daysLeft} &lt;= 0). */
    public static Content subscription(String name, String business, String plan, String endsOn, int daysLeft, String link) {
        boolean ended = daysLeft <= 0;
        String when = ended ? "ended on " + endsOn : daysLeft == 1 ? "ends tomorrow (" + endsOn + ")" : "ends in " + daysLeft + " days (" + endsOn + ")";
        String subject = ended ? "Your JurisOne subscription has ended" : "Your JurisOne subscription " + (daysLeft == 1 ? "ends tomorrow" : "ends in " + daysLeft + " days");
        String after = ended
                ? "Your team can still sign in, but nothing else works until it is renewed. You can still export your employee details."
                : "Renew it before then so your team keeps working without a break.";
        return new Content(subject,
                "Hi " + name + ",\n\nThe " + plan + " subscription for " + business + " " + when + ".\n" + after + "\n\nContact JurisOne to renew.\n" + link,
                shell(esc(subject), "<p>Hi " + esc(name) + ", the <b>" + esc(plan) + "</b> subscription for <b>" + esc(business) + "</b> " + esc(when) + ".</p>"
                        + "<p style=\"color:#5D5F6D\">" + esc(after) + " Contact JurisOne to renew.</p>" + button(link, "View subscription")));
    }

    public static Content reset(String name, String link, int minutes) {
        return new Content(
                "Reset your JurisOne password",
                "Hi " + name + ",\n\nUse this one-time link to choose a new password (valid " + minutes + " minutes):\n" + link
                        + "\n\nIf you didn't ask for this, you can ignore this email.",
                shell("Reset your password", "<p>Hi " + esc(name) + ", use this one-time link to choose a new password. It expires in " + minutes + " minutes.</p>"
                        + button(link, "Choose a new password")
                        + "<p style=\"color:#5D5F6D;font-size:13px\">If you didn't ask for this, ignore this email — your password stays the same.</p>"));
    }

    public static Content otp(String name, String code, int minutes) {
        return new Content(
                "Your JurisOne sign-in code: " + code,
                "Hi " + name + ",\n\nYour sign-in code is " + code + ". It works once and expires in " + minutes + " minutes."
                        + "\n\nIf you didn't try to sign in, you can ignore this email.",
                shell("Your sign-in code", "<p>Hi " + esc(name) + ", use this code to sign in. It works once and expires in " + minutes + " minutes.</p>"
                        + "<p style=\"font-size:28px;letter-spacing:6px;font-weight:600\">" + esc(code) + "</p>"
                        + "<p style=\"color:#5D5F6D;font-size:13px\">If you didn't try to sign in, ignore this email.</p>"));
    }
}
