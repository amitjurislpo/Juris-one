package com.jurislpo.jurisone.platform;

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
                        + link + "\n\nSign in afterwards with your employee ID or official email.",
                shell("Welcome, " + esc(name), "<p>Your JurisOne account <b>" + code + "</b> is ready. Set your password with this one-time link — it expires in "
                        + hours + " hours.</p>" + button(link, "Set my password")));
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

    public static Content reset(String name, String link, int minutes) {
        return new Content(
                "Reset your JurisOne password",
                "Hi " + name + ",\n\nUse this one-time link to choose a new password (valid " + minutes + " minutes):\n" + link
                        + "\n\nIf you didn't ask for this, you can ignore this email.",
                shell("Reset your password", "<p>Hi " + esc(name) + ", use this one-time link to choose a new password. It expires in " + minutes + " minutes.</p>"
                        + button(link, "Choose a new password")
                        + "<p style=\"color:#5D5F6D;font-size:13px\">If you didn't ask for this, ignore this email — your password stays the same.</p>"));
    }
}
