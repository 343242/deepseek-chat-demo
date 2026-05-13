-- ============================================================
-- V10: rag_document 时间列 TIMESTAMPTZ + team 表 DDL COMMENT
-- ============================================================

-- M6: rag_document 时间列统一 TIMESTAMPTZ
ALTER TABLE rag_document ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE rag_document ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'Asia/Shanghai';

-- L6: team_member 角色字段 COMMENT
COMMENT ON COLUMN team_member.role IS '10=MEMBER(默认) 20=ADMIN 30=CREATOR';
COMMENT ON COLUMN team_member.status IS '0=INACTIVE 1=ACTIVE';

-- team 表 COMMENT 补充
COMMENT ON COLUMN team.status IS '0=INACTIVE 1=ACTIVE';
COMMENT ON COLUMN team_upload_approval.status IS '0=PENDING 1=APPROVED 2=REJECTED';
