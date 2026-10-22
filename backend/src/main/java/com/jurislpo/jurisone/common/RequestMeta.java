package com.jurislpo.jurisone.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client IP and user agent: first X-Forwarded-For hop, else X-Real-IP; UA cut to 400 chars.
 * The web server in front (proxy) forwards these headers.
 */
public record RequestMeta(String ipAddress, String userAgent) {

    public static RequestMeta of(HttpServletRequest request) {
        String forwardedFor = request.getHeader("x-forwarded-for");
        String ipAddress = forwardedFor != null ? forwardedFor.split(",")[0].trim() : request.getHeader("x-real-ip");
        if (ipAddress != null && ipAddress.isEmpty()) ipAddress = null;
        String userAgent = request.getHeader("user-agent");
        return new RequestMeta(ipAddress, userAgent == null ? null : userAgent.substring(0, Math.min(400, userAgent.length())));
    }
}
