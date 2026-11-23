package com.documind.infrastructure.security;

import com.documind.exception.AuthStateStoreUnavailableException;
import com.documind.exception.InvalidTokenException;
import com.documind.service.auth.TokenBlacklistService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Objects;

/**
 * Stateless Bearer JWT filter constructing {@link CustomUserDetails} from verified token claims,
 * checking Redis {@code jti} blacklist fail-closed, skipping public login/refresh endpoints so stale
 * tokens cannot block re-authentication or rotation, and reusing {@link RequestAttributeSecurityContextRepository}
 * across {@link DispatcherType#ASYNC}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String DOCUMENTS_PREFIX = "/api/v1/documents/";
    private static final String CONTENT_SUFFIX = "/content";

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    public JwtAuthenticationFilter(
        JwtService jwtService,
        TokenBlacklistService tokenBlacklistService,
        ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
        @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.tokenBlacklistService = Objects.requireNonNull(
            tokenBlacklistService,
            "tokenBlacklistService must not be null"
        );
        this.authenticationEntryPoint = Objects.requireNonNull(
            authenticationEntryPoint,
            "authenticationEntryPoint must not be null"
        );
        this.handlerExceptionResolver = Objects.requireNonNull(
            handlerExceptionResolver,
            "handlerExceptionResolver must not be null"
        );
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String path = (servletPath != null && !servletPath.isEmpty()) ? servletPath : request.getRequestURI();
        if (HttpMethod.GET.matches(request.getMethod()) && isDocumentPreviewContentPath(path, request.getContextPath())) {
            return true;
        }
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return false;
        }
        return LOGIN_PATH.equals(path) || REFRESH_PATH.equals(path);
    }

    public static boolean isDocumentPreviewContentPath(String rawPath, String contextPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        String path = (contextPath != null && !contextPath.isEmpty() && rawPath.startsWith(contextPath))
            ? rawPath.substring(contextPath.length())
            : rawPath;
        if (!path.startsWith(DOCUMENTS_PREFIX) || !path.endsWith(CONTENT_SUFFIX)) {
            return false;
        }
        String segment = path.substring(DOCUMENTS_PREFIX.length(), path.length() - CONTENT_SUFFIX.length());
        return !segment.isEmpty() && segment.indexOf('/') < 0;
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
        if (DispatcherType.ASYNC.equals(request.getDispatcherType())
            && SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawAuthHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (rawAuthHeader == null || rawAuthHeader.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        String trimmedHeader = rawAuthHeader.trim();
        if (!trimmedHeader.regionMatches(true, 0, "Bearer", 0, "Bearer".length())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (trimmedHeader.length() <= BEARER_PREFIX.length()
            || !Character.isWhitespace(trimmedHeader.charAt("Bearer".length()))) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.writeUnauthorized(
                request,
                response,
                InvalidTokenException.CODE_INVALID_TOKEN,
                "Invalid Token",
                "Bearer token is missing"
            );
            return;
        }

        String token = trimmedHeader.substring(BEARER_PREFIX.length()).trim();
        CustomUserDetails principal;
        try {
            AuthenticatedTokenClaims claims = jwtService.parseAndValidateAccessToken(token);
            if (tokenBlacklistService.isTokenRevoked(claims.jti(), claims.userId(), claims.issuedAt())) {
                throw new InvalidTokenException(
                    InvalidTokenException.CODE_TOKEN_REVOKED,
                    "Access token has been revoked"
                );
            }
            principal = CustomUserDetails.fromClaims(claims);
        } catch (InvalidTokenException ex) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.writeUnauthorized(
                request,
                response,
                ex.errorCode(),
                resolveUnauthorizedTitle(ex.errorCode()),
                ex.getMessage()
            );
            return;
        } catch (AuthStateStoreUnavailableException ex) {
            SecurityContextHolder.clearContext();
            handlerExceptionResolver.resolveException(request, response, null, ex);
            return;
        }

        setAuthenticationContext(principal, request, response);
        filterChain.doFilter(request, response);
    }

    private void setAuthenticationContext(
        CustomUserDetails principal,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            principal.getAuthorities()
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static String resolveUnauthorizedTitle(String errorCode) {
        if (InvalidTokenException.CODE_TOKEN_EXPIRED.equals(errorCode)) {
            return "Token Expired";
        }
        if (InvalidTokenException.CODE_TOKEN_REVOKED.equals(errorCode)) {
            return "Token Revoked";
        }
        return "Invalid Token";
    }
}
