-- ============================================================
-- V3__seed_roles_permissions.sql — 初始角色、权限及绑定
-- ============================================================

-- ==================== 角色 ====================

INSERT INTO sys_role (role_name, role_desc)
SELECT 'ADMIN', '系统管理员'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_name = 'ADMIN');

INSERT INTO sys_role (role_name, role_desc)
SELECT 'USER', '普通用户'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_name = 'USER');

-- ==================== 权限 ====================

INSERT INTO sys_permission (permission_name, permission_desc, resource_type, resource_key)
SELECT p.permission_name, p.permission_desc, p.resource_type, p.resource_key
FROM (VALUES
    ('chat:send',           '发送聊天消息', 'API', 'POST /api/chat'),
    ('chat:stream',         '流式聊天',     'API', 'GET /api/chat/stream'),
    ('conversation:manage', '管理对话记录', 'API', '*'),
    ('model:config',        '配置模型参数', 'API', '*'),
    ('prompt:manage',       '管理系统提示词','API', '*'),
    ('usage:view',          '查看用量统计', 'API', '*'),
    ('user:manage',         '管理用户',     'API', '*'),
    ('role:manage',         '管理角色权限', 'API', '*')
) AS p(permission_name, permission_desc, resource_type, resource_key)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_name = p.permission_name
);

-- ==================== 角色-权限绑定 ====================

-- ADMIN 拥有全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_name = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission
    WHERE role_id = r.id AND permission_id = p.id
  );

-- USER 拥有基础权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_name = 'USER'
  AND p.permission_name IN ('chat:send', 'chat:stream', 'conversation:manage', 'usage:view')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission
    WHERE role_id = r.id AND permission_id = p.id
  );

-- ==================== 初始管理员 ====================
-- 密码: admin123 (BCrypt)

INSERT INTO sys_user (id, username, password, nickname, status)
SELECT 1, 'admin', '$2a$10$kK3Qf5iixQQ.De4smj0k9OE/7A.nxhHAe.6U2R11foJ5WIdup2q8.', '系统管理员', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
CROSS JOIN sys_role r
WHERE u.username = 'admin' AND r.role_name = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role
    WHERE user_id = u.id AND role_id = r.id
  );
