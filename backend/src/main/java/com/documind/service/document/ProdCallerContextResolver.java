package com.documind.service.document;

import com.documind.exception.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Fail-closed {@link CallerContextResolver} active by default in all non-opted-in profiles
 * (including {@code prod}, {@code staging}, {@code test}, and unprofiled runs) until {@code DA-12} wires
 * JWT authentication from {@code SecurityContextHolder}. Ignores all untrusted client identity headers/params.
 */
@Component
@Conditional(FailClosedCallerContextCondition.class)
public class ProdCallerContextResolver implements CallerContextResolver {

    @Override
    public CallerContext resolve(HttpServletRequest request) {
        throw new AuthenticationRequiredException("Authenticated security principal is required");
    }
}
