-- ============================================================
-- V6__backfill_conversation_and_message.sql
--
-- 从 spring_ai_chat_memory 回填数据到 conversation 和 message 表。
-- 幂等安全：所有 INSERT 使用 NOT EXISTS 判断。
-- 安全守卫：若 spring_ai_chat_memory 表不存在则跳过（删库重建场景）。
-- ============================================================

DO $$
BEGIN
    IF to_regclass('public.spring_ai_chat_memory') IS NOT NULL THEN
        -- ======================== 回填会话 ========================

        INSERT INTO conversation (conversation_id, user_id, title, title_source, status, message_count, last_message_at, created_at, updated_at)
        SELECT
            sub.conversation_id,
            COALESCE(
                CAST(substring(sub.conversation_id FROM 'u_(\d+)_') AS BIGINT),
                0
            ) AS user_id,
            NULL AS title,
            'SYSTEM' AS title_source,
            'ACTIVE' AS status,
            sub.cnt AS message_count,
            sub.last_at AS last_message_at,
            sub.first_at AS created_at,
            NOW() AS updated_at
        FROM (
            SELECT
                conversation_id,
                COUNT(*) AS cnt,
                MAX(created_at) AS last_at,
                MIN(created_at) AS first_at
            FROM spring_ai_chat_memory
            GROUP BY conversation_id
        ) sub
        WHERE NOT EXISTS (
            SELECT 1 FROM conversation c WHERE c.conversation_id = sub.conversation_id
        );

        -- ======================== 回填消息 ========================
        -- 注意：不建立 parent_id 关系（历史数据无法推断树结构），全部作为根消息

        INSERT INTO message (conversation_id, parent_id, role, content, status, created_at, updated_at)
        SELECT
            m.conversation_id,
            NULL AS parent_id,
            UPPER(m.type) AS role,
            m.content,
            'FINISHED' AS status,
            m.created_at,
            m.created_at AS updated_at
        FROM spring_ai_chat_memory m
        WHERE NOT EXISTS (
            SELECT 1 FROM message msg
            WHERE msg.conversation_id = m.conversation_id
              AND msg.created_at = m.created_at
              AND msg.role = UPPER(m.type)
        )
        ORDER BY m.created_at ASC;
    ELSE
        RAISE NOTICE 'spring_ai_chat_memory does not exist, skipping backfill';
    END IF;
END
$$;
