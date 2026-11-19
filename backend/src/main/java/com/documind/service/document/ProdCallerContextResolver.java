package com.documind.service.document;

import com.documind.exception.AuthenticationRequiredException;
import com.documind.infrastructure.security.ClientIpResolver;
import com.documind.infrastructure.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Production {@link CallerContextResolver} active whenever {@code dev-caller-fallback} is disabled.
 * Resolves caller identity strictly from the verified {@link CustomUserDetails} principal in
 * {@link SecurityContextHolder}, ignoring all untrusted client headers and failing closed if absent.
 */
@Component
@Conditional(FailClosedCallerContextCondition.class)
public class ProdCallerContextResolver implements CallerContextResolver {

    @Override
    public CallerContext resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
            && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof CustomUserDetails principal) {
            return new CallerContext(
                principal.userId(),
                principal.departmentId(),
                principal.role(),
                ClientIpResolver.resolve(request)
            );
        }
        throw new AuthenticationRequiredException("Authenticated security principal is required");
    }
}
