-- Allow the short pending state used between initial insert and MCP handshake.
ALTER TABLE mcp_server_config
    DROP CONSTRAINT IF EXISTS mcp_server_config_state;

UPDATE mcp_tool_config
SET intent = 'GENERAL_TOOL'
WHERE intent IS NULL
   OR intent NOT IN ('DIRECT_ANSWER', 'RETRIEVAL', 'DEEP_RETRIEVAL', 'GENERAL_TOOL');

UPDATE mcp_tool_config
SET risk = 'low'
WHERE risk IS NULL OR risk NOT IN ('low', 'high');

ALTER TABLE mcp_tool_config
    ALTER COLUMN intent SET DEFAULT 'GENERAL_TOOL',
    ALTER COLUMN intent SET NOT NULL,
    ALTER COLUMN risk SET DEFAULT 'low',
    ALTER COLUMN risk SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'mcp_tool_config_intent'
          AND conrelid = 'mcp_tool_config'::regclass
    ) THEN
        ALTER TABLE mcp_tool_config
            ADD CONSTRAINT mcp_tool_config_intent CHECK (
                intent IN ('DIRECT_ANSWER', 'RETRIEVAL', 'DEEP_RETRIEVAL', 'GENERAL_TOOL')
            );
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'mcp_tool_config_risk'
          AND conrelid = 'mcp_tool_config'::regclass
    ) THEN
        ALTER TABLE mcp_tool_config
            ADD CONSTRAINT mcp_tool_config_risk CHECK (risk IN ('low', 'high'));
    END IF;
END $$;

WITH ranked_tools AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY server_id, tool_name
               ORDER BY updated_at DESC, version DESC, id DESC
           ) AS row_number
    FROM mcp_tool_config
)
DELETE FROM mcp_tool_config
WHERE id IN (
    SELECT id FROM ranked_tools WHERE row_number > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mcp_tool_server_tool
    ON mcp_tool_config (server_id, tool_name);
