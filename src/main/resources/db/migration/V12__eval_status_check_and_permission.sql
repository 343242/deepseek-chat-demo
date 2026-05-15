-- RAG 评估系统审查修复补充
-- 1. evaluation_run.status CHECK 约束
-- 2. evaluation_dataset_item.status CHECK 约束
-- 3. 新增 evaluation:manage 权限

-- status CHECK 约束
ALTER TABLE evaluation_run
    ADD CONSTRAINT chk_eval_run_status
        CHECK (status IN ('pending', 'running', 'completed', 'failed'));

ALTER TABLE evaluation_dataset_item
    ADD CONSTRAINT chk_eval_item_status
        CHECK (status IN ('draft', 'approved', 'rejected'));

-- 新增评估管理权限
INSERT INTO sys_permission (permission_name, permission_desc, resource_type, resource_key)
SELECT 'evaluation:manage', '管理 RAG 评估系统', 'API', '/api/evaluation/**'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_name = 'evaluation:manage');

-- ADMIN 角色绑定新权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_name = 'ADMIN'
  AND p.permission_name = 'evaluation:manage'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);
