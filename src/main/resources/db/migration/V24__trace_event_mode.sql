-- V24__trace_event_mode.sql — trace_event 增加 mode 列（区分 CHAT / AGENT 检索路径）
-- 幂等：ADD COLUMN IF NOT EXISTS，可重复执行。
ALTER TABLE trace_event ADD COLUMN IF NOT EXISTS mode VARCHAR(16);

-- 回填历史数据：全部标记为 UNKNOWN（无法追溯历史记录的来源路径）
UPDATE trace_event SET mode = 'UNKNOWN' WHERE mode IS NULL;

-- 设默认值 + NOT NULL（回填后新数据由应用层写入，兜底 'UNKNOWN'）
ALTER TABLE trace_event
    ALTER COLUMN mode SET DEFAULT 'UNKNOWN',
    ALTER COLUMN mode SET NOT NULL;

COMMENT ON COLUMN trace_event.mode IS '检索路径模式：CHAT（Chat 检索链路）/ AGENT（Agent Tool 链路）/ UNKNOWN（无法判定）';
