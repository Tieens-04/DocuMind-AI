package com.documind.service.document;

import com.documind.exception.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-closed {@link CallerContextResolver} active in the {@code prod} profile until {@code DA-12} wires
 * JWT authentication from {@code SecurityContextHolder}. Ignores all untrusted client identity headers/params.
 */
@Component
@Profile("prod")
public class ProdCallerContextResolver implements CallerContextResolver {

    @Override
    public CallerContext resolve(HttpServletRequest request) {
        throw new AuthenticationRequiredException("Authenticated security principal is required");
    }
}
