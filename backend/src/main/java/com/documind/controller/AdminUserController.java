package com.documind.controller;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminResetPasswordRequest;
import com.documind.controller.dto.admin.AdminUserResponse;
import com.documind.controller.dto.admin.CreateUserRequest;
import com.documind.controller.dto.admin.UpdateUserRequest;
import com.documind.domain.enums.Role;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Administrative REST endpoints for user account provisioning, role/department governance,
 * soft activation/deactivation, and password resets (strictly {@code SYSTEM_ADMIN}).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Tag(name = "Admin Users", description = "Enterprise user account administration restricted to SYSTEM_ADMIN")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = Objects.requireNonNull(adminUserService, "adminUserService must not be null");
    }

    @PostMapping
    @Operation(summary = "Create user", description = "Provisions a new enterprise user assigned to a department and RBAC role")
    public ResponseEntity<AdminUserResponse> createUser(
        @Valid @RequestBody CreateUserRequest request,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        AdminUserResponse created = adminUserService.createUser(request, requirePrincipalId(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Operation(summary = "List users", description = "Returns paginated users filtered by department, role, status, or keyword")
    public PageResponse<AdminUserResponse> listUsers(
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        @RequestParam(name = "role", required = false) Role role,
        @RequestParam(name = "isActive", required = false) Boolean isActive,
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminUserService.listUsers(departmentId, role, isActive, query, page, size, requirePrincipalId(principal));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID", description = "Returns user account metadata and assigned department")
    public AdminUserResponse getUser(
        @PathVariable("id") UUID id,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminUserService.getUser(id, requirePrincipalId(principal));
    }

    @PatchMapping("/{id}")
    @Operation(
        summary = "Update user",
        description = "Updates user profile, role, department, or activation state and revokes active JWT sessions on security changes"
    )
    public AdminUserResponse updateUser(
        @PathVariable("id") UUID id,
        @Valid @RequestBody UpdateUserRequest request,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminUserService.updateUser(id, request, requirePrincipalId(principal));
    }

    @PutMapping("/{id}/password")
    @Operation(
        summary = "Reset user password",
        description = "Resets a user's password and immediately revokes all active access and refresh tokens"
    )
    public ResponseEntity<Void> resetPassword(
        @PathVariable("id") UUID id,
        @Valid @RequestBody AdminResetPasswordRequest request,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        adminUserService.resetPassword(id, request, requirePrincipalId(principal));
        return ResponseEntity.noContent().build();
    }

    private static UUID requirePrincipalId(CustomUserDetails principal) {
        if (principal == null) {
            throw new AuthenticationRequiredException("Authenticated security principal is required");
        }
        return principal.userId();
    }
}
