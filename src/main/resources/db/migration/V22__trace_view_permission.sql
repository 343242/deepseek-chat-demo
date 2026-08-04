-- 新增 trace:view 权限 — 管理员查看链路追踪（trace_event + agent_session_event 两个端点）

INSERT INTO sys_permission (permission_name, permission_desc, resource_type, resource_key)
SELECT 'trace:view', '查看链路追踪（检索步骤 + Agent 事件流）', 'API', '/api/admin/**'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_name = 'trace:view');

-- ADMIN 角色绑定新权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_name = 'ADMIN'
  AND p.permission_name = 'trace:view'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
