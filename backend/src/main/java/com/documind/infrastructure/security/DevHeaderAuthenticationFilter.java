package com.documind.infrastructure.security;

import com.documind.exception.InvalidDocumentRequestException;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DevCallerFallbackEnabledCondition;
import com.documind.service.document.DevFallbackCallerContextResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Opt-in legacy test/dev filter active strictly when {@code documind.security.dev-caller-fallback.enabled=true}
 * and {@code prod} is inactive. Never applies to {@code /api/v1/auth/**}, {@code /api/v1/admin/**},
 * or requests carrying an {@code Authorization} header.
 */
@Component
@Conditional(DevCallerFallbackEnabledCondition.class)
public class DevHeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth";
    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";

    private final DevFallbackCallerContextResolver devFallbackCallerContextResolver;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    public DevHeaderAuthenticationFilter(
        DevFallbackCallerContextResolver devFallbackCallerContextResolver,
        @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.devFallbackCallerContextResolver = Objects.requireNonNull(
            devFallbackCallerContextResolver,
            "devFallbackCallerContextResolver must not be null"
        );
        this.handlerExceptionResolver = Objects.requireNonNull(
            handlerExceptionResolver,
            "handlerExceptionResolver must not be null"
        );
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        String rawAuthorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (rawAuthorization != null && !rawAuthorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (isProtectedPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        CallerContext caller;
        try {
            caller = devFallbackCallerContextResolver.resolve(request);
        } catch (InvalidDocumentRequestException ex) {
            SecurityContextHolder.clearContext();
            handlerExceptionResolver.resolveException(request, response, null, ex);
            return;
        }

        PreAuthenticatedAuthenticationToken authentication = new PreAuthenticatedAuthenticationToken(
            caller,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + caller.role().name()))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        filterChain.doFilter(request, response);
    }

    private static boolean isProtectedPath(HttpServletRequest request) {
        if (matchesAnyProtectedPrefix(request.getServletPath())) {
            return true;
        }
        String requestUri = request.getRequestURI();
        if (requestUri == null) {
            return false;
        }
        if (matchesAnyProtectedPrefix(requestUri)) {
            return true;
        }
        String withoutContext = stripContextPath(requestUri, request.getContextPath());
        if (matchesAnyProtectedPrefix(withoutContext)) {
            return true;
        }
        try {
            String decoded = UriUtils.decode(withoutContext, StandardCharsets.UTF_8);
            return matchesAnyProtectedPrefix(decoded);
        } catch (IllegalArgumentException ex) {
            // Fail closed on malformed percent-encoding so dev fallback cannot bypass protected prefixes
            return true;
        }
    }

    private static String stripContextPath(String requestUri, String contextPath) {
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private static boolean matchesAnyProtectedPrefix(String path) {
        return matchesProtectedPrefix(path, AUTH_PATH_PREFIX)
            || matchesProtectedPrefix(path, ADMIN_PATH_PREFIX);
    }

    private static boolean matchesProtectedPrefix(String path, String prefix) {
        return path != null && (path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
