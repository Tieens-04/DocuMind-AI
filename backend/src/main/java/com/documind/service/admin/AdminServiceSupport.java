package com.documind.service.admin;

import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shared package-private governance, transaction, paging, and search normalization helpers
 * for administrative department and user services.
 */
final class AdminServiceSupport {

    static final int MAX_PAGE_SIZE = 100;

    private AdminServiceSupport() {
    }

    static UserEntity verifyActiveSystemAdmin(UserRepository userRepository, UUID actorUserId) {
        if (actorUserId == null) {
            throw new AuthenticationRequiredException("Authenticated administrator principal is required");
        }
        UserEntity actor = userRepository.findById(actorUserId)
            .orElseThrow(() -> new AccessDeniedException("Administrator account no longer exists"));
        if (!actor.isActive() || actor.getRole() != Role.SYSTEM_ADMIN) {
            throw new AccessDeniedException("Administrator account is inactive or lacks SYSTEM_ADMIN privileges");
        }
        return actor;
    }

    static <T> T executeInTransaction(
        PlatformTransactionManager transactionManager,
        boolean readOnly,
        Supplier<T> action
    ) {
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(readOnly);
        return template.execute(status -> action.get());
    }

    static void validatePaging(int page, int size) {
        if (page < 0) {
            throw new InvalidAdminRequestException("Page index must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidAdminRequestException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    static String normalizeSearchQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return Normalizer.normalize(query.trim(), Normalizer.Form.NFC);
    }
}
