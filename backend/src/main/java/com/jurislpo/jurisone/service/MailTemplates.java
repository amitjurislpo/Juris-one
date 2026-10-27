package com.jurislpo.jurisone.service;

/** Email bodies. */
public final class MailTemplates {
    private MailTemplates() {}

    public record Content(String subject, String text, String html) {}

    static String escapeHtml(String text) {
        StringBuilder builder = new StringBuilder();
        for (char character : text.toCharArray()) {
            switch (character) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '"' -> builder.append("&quot;");
                case '\'' -> builder.append("&#39;");
                default -> builder.append(character);
            }
        }
        return builder.toString();
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

    public static Content decision(String name, String what, boolean approved, String by, String note) {
        String verb = approved ? "approved" : "declined";
        boolean hasNote = note != null && !note.isEmpty();
        return new Content(
                what + " " + verb,
                "Hi " + name + ",\n\nYour " + what.toLowerCase() + " was " + verb + " by " + by + "." + (hasNote ? "\n\nNote: " + note : "")
                        + "\n\nOpen JurisOne for details.",
                shell(escapeHtml(what) + " " + verb, "<p>Hi " + escapeHtml(name) + ", your " + escapeHtml(what.toLowerCase()) + " was <b>" + verb + "</b> by " + escapeHtml(by) + ".</p>"
                        + (hasNote ? "<p style=\"color:#5D5F6D\">Note: " + escapeHtml(note) + "</p>" : "")));
    }

    /** An HR letter was issued to the employee; the PDF is attached and also lives under My letters. */
    public static Content letter(String name, String business, String subject, String reference, String link) {
        return new Content(
                subject + " (" + reference + ")",
                "Hi " + name + ",\n\n" + business + " has issued you a letter: " + subject + " (reference " + reference + "). The PDF is attached; you can also find it under My letters in JurisOne: " + link,
                shell(escapeHtml(subject), "<p>Hi " + escapeHtml(name) + ", " + escapeHtml(business) + " has issued you a letter: <b>" + escapeHtml(subject) + "</b> (reference " + escapeHtml(reference) + ").</p>"
                        + "<p style=\"color:#5D5F6D\">The PDF is attached. You can also find it under <a href=\"" + escapeHtml(link) + "\">My letters</a> in JurisOne.</p>"));
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
                shell(escapeHtml(subject), "<p>Hi " + escapeHtml(name) + ", the <b>" + escapeHtml(plan) + "</b> subscription for <b>" + escapeHtml(business) + "</b> " + escapeHtml(when) + ".</p>"
                        + "<p style=\"color:#5D5F6D\">" + escapeHtml(after) + " Contact JurisOne to renew.</p>" + button(link, "View subscription")));
    }

    /** HR started this person's onboarding: the portal link and how to sign in (there is no password, a code is emailed each time). */
    public static Content onboardingInvitation(String name, String email, String link) {
        String subject = "Welcome aboard! Complete your onboarding on JurisOne";
        return new Content(subject,
                "Hi " + name + ",\n\nGlad to have you onboard! We have created a new self-service account for you. You can now start by providing us your personal, academic and professional information."
                        + "\n\nLet's Get Started: " + link + "\n\nSign in with your work email " + email + ". A one-time code is emailed to you each time you sign in."
                        + "\n\nRegards,\nHR Team",
                shell("Welcome aboard!", "<p>Hi " + escapeHtml(name) + ",</p><p><b>Glad to have you onboard!</b> We have created a new self-service account for you. "
                        + "You can now start by providing us your personal, academic and professional information.</p>" + button(link, "Let's Get Started")
                        + "<p>Sign in with your work email <b>" + escapeHtml(email) + "</b>. A one-time code is emailed to you each time you sign in.</p>"
                        + "<p style=\"color:#5D5F6D\">Regards,<br>HR Team</p>"));
    }

    public static Content otp(String name, String code, int minutes) {
        return new Content(
                "Your JurisOne sign-in code: " + code,
                "Hi " + name + ",\n\nYour sign-in code is " + code + ". It works once and expires in " + minutes + " minutes."
                        + "\n\nIf you didn't try to sign in, you can ignore this email.",
                shell("Your sign-in code", "<p>Hi " + escapeHtml(name) + ", use this code to sign in. It works once and expires in " + minutes + " minutes.</p>"
                        + "<p style=\"font-size:28px;letter-spacing:6px;font-weight:600\">" + escapeHtml(code) + "</p>"
                        + "<p style=\"color:#5D5F6D;font-size:13px\">If you didn't try to sign in, ignore this email.</p>"));
    }

    /** Sent instead of a code when the account can't sign in, so the person learns why no code arrives. */
    public static Content noCode(String name, String reason) {
        return new Content(
                "Your JurisOne sign-in request",
                "Hi " + name + ",\n\nSomeone asked for a sign-in code for this email, but no code was sent. " + reason
                        + "\n\nIf this wasn't you, you can ignore this email.",
                shell("No sign-in code sent", "<p>Hi " + escapeHtml(name) + ", a sign-in code was requested for this email, but none was sent.</p><p>" + escapeHtml(reason) + "</p>"
                        + "<p style=\"color:#5D5F6D;font-size:13px\">If this wasn't you, ignore this email.</p>"));
    }

    /** HR approved the employee's onboarding: the portal is open. */
    public static Content onboardingApproved(String name, String by) {
        return new Content(
                "Onboarding approved",
                "Hi " + name + ",\n\nYour onboarding was approved by " + by + ". You can now use JurisOne.\n\nSign in to get started.",
                shell("Onboarding approved", "<p>Hi " + escapeHtml(name) + ", your onboarding was <b>approved</b> by " + escapeHtml(by) + ".</p>"
                        + "<p style=\"color:#5D5F6D\">You can now use JurisOne. Sign in to get started.</p>"));
    }

    /** HR sent the onboarding back: the overall comment and, per flagged section, {@code {section title, comment}}. */
    public static Content onboardingChangesRequested(String name, String by, String comment, java.util.List<String[]> sections) {
        StringBuilder text = new StringBuilder("Hi " + name + ",\n\n" + by + " reviewed your onboarding and needs a few changes.\n\n" + comment + "\n\nPlease correct these sections:\n");
        StringBuilder list = new StringBuilder("<ul style=\"padding-left:18px\">");
        for (String[] section : sections) {
            text.append("- ").append(section[0]).append(": ").append(section[1]).append('\n');
            list.append("<li><b>").append(escapeHtml(section[0])).append("</b>: ").append(escapeHtml(section[1])).append("</li>");
        }
        list.append("</ul>");
        text.append("\nSign in to JurisOne, fix them and submit again.");
        return new Content(
                "Changes needed in your onboarding",
                text.toString(),
                shell("Changes needed in your onboarding", "<p>Hi " + escapeHtml(name) + ", " + escapeHtml(by) + " reviewed your onboarding and needs a few changes.</p>"
                        + "<p style=\"color:#5D5F6D\">" + escapeHtml(comment) + "</p><p>Please correct these sections:</p>" + list
                        + "<p style=\"color:#5D5F6D\">Sign in to JurisOne, fix them and submit again.</p>"));
    }
}
