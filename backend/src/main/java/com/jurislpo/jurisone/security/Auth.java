package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UnauthenticatedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Permission checks for services and controllers — the Java counterpart of {@code assertPerm} / {@code requirePage}.
 * Same rules and messages: signed out → "Your session has ended…", missing permission → "You don't have permission…".
 */
@Component
public class Auth {

    /** The current user, or null when signed out. */
    public CurrentUser current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof CurrentUser u ? u : null;
    }

    /** Signed in, holding any one of {@code perms} (none = just signed in). */
    public CurrentUser assertPerm(String... perms) {
        CurrentUser u = current();
        if (u == null) throw new UnauthenticatedException();
        if (perms.length > 0 && !u.can(perms)) throw new ForbiddenException();
        return u;
    }

    /** A page's nav entry must be allowed (incl. "has direct reports" for My team). */
    public CurrentUser requirePage(String navId) {
        CurrentUser u = current();
        if (u == null) throw new UnauthenticatedException();
        if (!u.navAllowed(navId)) throw new ForbiddenException();
        return u;
    }
}
