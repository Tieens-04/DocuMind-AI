-- ============================================================================
-- DocuMind AI — V3 Development Seed Data (Excluded in prod via application-prod.yml)
-- Dev password for all sample accounts: DocuMind@2026! (BCrypt cost 10)
-- ============================================================================

INSERT INTO departments (id, code, name, description, cache_version)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'IT', 'Công nghệ Thông tin', 'Phòng Công nghệ Thông tin', 1),
    ('22222222-2222-2222-2222-222222222222', 'HR', 'Hành chính Nhân sự', 'Phòng Hành chính Nhân sự', 1),
    ('33333333-3333-3333-3333-333333333333', 'SALES', 'Kinh doanh & Phát triển Thị trường', 'Phòng Kinh doanh & Phát triển Thị trường', 1)
ON CONFLICT DO NOTHING;

INSERT INTO users (id, department_id, email, password_hash, full_name, role, is_active)
VALUES
    (
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        '11111111-1111-1111-1111-111111111111',
        'admin@documind.ai',
        '$2a$10$q6.0PtDMsJDIKJWkOmdXFefwSPRiXyK4dWz5JIOMMZUEt/e35s8yi',
        'System Administrator',
        'SYSTEM_ADMIN',
        TRUE
    ),
    (
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        '22222222-2222-2222-2222-222222222222',
        'manager.hr@documind.ai',
        '$2a$10$q6.0PtDMsJDIKJWkOmdXFefwSPRiXyK4dWz5JIOMMZUEt/e35s8yi',
        'HR Department Manager',
        'DEPARTMENT_MANAGER',
        TRUE
    ),
    (
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        '33333333-3333-3333-3333-333333333333',
        'employee.sales@documind.ai',
        '$2a$10$q6.0PtDMsJDIKJWkOmdXFefwSPRiXyK4dWz5JIOMMZUEt/e35s8yi',
        'Sales Standard Employee',
        'STANDARD_EMPLOYEE',
        TRUE
    )
ON CONFLICT DO NOTHING;
