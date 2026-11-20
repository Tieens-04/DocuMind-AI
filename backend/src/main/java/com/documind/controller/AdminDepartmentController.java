package com.documind.controller;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminDepartmentResponse;
import com.documind.controller.dto.admin.CreateDepartmentRequest;
import com.documind.controller.dto.admin.UpdateDepartmentRequest;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.admin.AdminDepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Administrative REST endpoints for enterprise department CRUD (strictly {@code SYSTEM_ADMIN}).
 */
@RestController
@RequestMapping("/api/v1/admin/departments")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Tag(name = "Admin Departments", description = "Department CRUD management restricted to SYSTEM_ADMIN")
public class AdminDepartmentController {

    private final AdminDepartmentService adminDepartmentService;

    public AdminDepartmentController(AdminDepartmentService adminDepartmentService) {
        this.adminDepartmentService = Objects.requireNonNull(
            adminDepartmentService,
            "adminDepartmentService must not be null"
        );
    }

    @PostMapping
    @Operation(summary = "Create department", description = "Creates a new department with a unique normalized code")
    public ResponseEntity<AdminDepartmentResponse> createDepartment(
        @Valid @RequestBody CreateDepartmentRequest request,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        AdminDepartmentResponse created = adminDepartmentService.createDepartment(request, requirePrincipalId(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Operation(summary = "List departments", description = "Returns paginated departments with optional keyword search")
    public PageResponse<AdminDepartmentResponse> listDepartments(
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminDepartmentService.listDepartments(query, page, size, requirePrincipalId(principal));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get department by ID", description = "Returns metadata and cache version for a single department")
    public AdminDepartmentResponse getDepartment(
        @PathVariable("id") UUID id,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminDepartmentService.getDepartment(id, requirePrincipalId(principal));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update department", description = "Partially updates department code, name, or description")
    public AdminDepartmentResponse updateDepartment(
        @PathVariable("id") UUID id,
        @Valid @RequestBody UpdateDepartmentRequest request,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminDepartmentService.updateDepartment(id, request, requirePrincipalId(principal));
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete unused department",
        description = "Hard-deletes a department only when zero users and zero documents reference it; otherwise returns 409"
    )
    public ResponseEntity<Void> deleteDepartment(
        @PathVariable("id") UUID id,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        adminDepartmentService.deleteDepartment(id, requirePrincipalId(principal));
        return ResponseEntity.noContent().build();
    }

    private static UUID requirePrincipalId(CustomUserDetails principal) {
        if (principal == null) {
            throw new AuthenticationRequiredException("Authenticated security principal is required");
        }
        return principal.userId();
    }
}
