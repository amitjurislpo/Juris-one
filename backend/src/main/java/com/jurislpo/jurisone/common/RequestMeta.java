package com.jurislpo.jurisone.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client IP and user agent (src/lib/request.ts): first X-Forwarded-For hop, else X-Real-IP; UA cut to 400 chars.
 * The Next.js app forwards these headers when it calls the API on the browser's behalf.
 */
public record RequestMeta(String ip, String userAgent) {

    public static RequestMeta of(HttpServletRequest req) {
        String fwd = req.getHeader("x-forwarded-for");
        String ip = fwd != null ? fwd.split(",")[0].trim() : req.getHeader("x-real-ip");
        if (ip != null && ip.isEmpty()) ip = null;
        String ua = req.getHeader("user-agent");
        return new RequestMeta(ip, ua == null ? null : ua.substring(0, Math.min(400, ua.length())));
    }
}
