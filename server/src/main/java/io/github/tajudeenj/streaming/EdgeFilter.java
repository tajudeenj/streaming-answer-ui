package io.github.tajudeenj.streaming;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Request id, security headers, cache policy for static assets, and CORS only for an explicitly configured origin. */
public final class EdgeFilter extends OncePerRequestFilter {
    static final String REQUEST_ID = "X-Request-Id";
    static final String REQUEST_ID_ATTR = "policyDesk.requestId";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
            + "font-src 'self' https://fonts.gstatic.com; connect-src 'self'; img-src 'self' data:; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    private final String allowedOrigin;

    public EdgeFilter(String allowedOrigin) { this.allowedOrigin = allowedOrigin; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(REQUEST_ID);
        String rid = incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        req.setAttribute(REQUEST_ID_ATTR, rid);
        res.setHeader(REQUEST_ID, rid);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        res.setHeader("Content-Security-Policy", CSP);

        String path = req.getRequestURI();
        if (!path.startsWith("/api/") && !path.startsWith("/actuator/")) {
            res.setHeader("Cache-Control", path.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache");
        }

        String origin = req.getHeader("Origin");
        boolean cors = !allowedOrigin.isEmpty() && allowedOrigin.equals(origin);
        if (cors) {
            res.setHeader("Access-Control-Allow-Origin", origin);
            res.setHeader("Vary", "Origin");
            res.setHeader("Access-Control-Expose-Headers", REQUEST_ID + ", Retry-After");
        }
        if ("OPTIONS".equals(req.getMethod())) {
            if (cors) {
                res.setHeader("Access-Control-Allow-Methods", "GET, POST");
                res.setHeader("Access-Control-Allow-Headers", "Content-Type, " + REQUEST_ID);
                res.setHeader("Access-Control-Max-Age", "600");
            }
            res.setStatus(cors ? 204 : 403);
            return;
        }
        chain.doFilter(req, res);
    }
}
