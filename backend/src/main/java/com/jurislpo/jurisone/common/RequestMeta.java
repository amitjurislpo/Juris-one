package com.jurislpo.jurisone.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client IP and user agent: the LAST X-Forwarded-For hop, else X-Real-IP; UA cut to 400 chars.
 * The web server in front (proxy) appends the address it saw to X-Forwarded-For, so the last hop is the one our proxy
 * vouches for; earlier hops are whatever the client sent and are never trusted (they would defeat the per-IP limits).
 */
public record RequestMeta(String ipAddress, String userAgent) {

    public static RequestMeta of(HttpServletRequest request) {
        String forwardedFor = request.getHeader("x-forwarded-for");
        String ipAddress = forwardedFor != null ? forwardedFor.substring(forwardedFor.lastIndexOf(',') + 1).trim() : request.getHeader("x-real-ip");
        if (ipAddress != null && ipAddress.isEmpty()) ipAddress = null;
        String userAgent = request.getHeader("user-agent");
        return new RequestMeta(ipAddress, userAgent == null ? null : userAgent.substring(0, Math.min(400, userAgent.length())));
    }
}
