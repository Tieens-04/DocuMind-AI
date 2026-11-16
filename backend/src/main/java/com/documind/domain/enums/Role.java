package com.documind.domain.enums;

/**
 * RBAC roles enforced across document management, vector retrieval, and audit governance.
 */
public enum Role {
    SYSTEM_ADMIN(3),
    DEPARTMENT_MANAGER(2),
    STANDARD_EMPLOYEE(1);

    private final int privilegeRank;

    Role(int privilegeRank) {
        this.privilegeRank = privilegeRank;
    }

    public boolean canAssume(Role requestedRole) {
        return requestedRole != null && this.privilegeRank >= requestedRole.privilegeRank;
    }
}
