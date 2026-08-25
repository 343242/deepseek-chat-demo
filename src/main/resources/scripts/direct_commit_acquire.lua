-- direct_commit_acquire.lua
-- 直传 commit 状态机 CAS：ACTIVE → COMMITTING 抢占 + 短租约 + 幂等/冲突/接管判定
--
-- KEYS[1] = direct:session:{sessionId}（会话 Hash，status 字段驱动状态机）
-- KEYS[2] = direct:commit-lease:{sessionId}（COMMITTING 短租约子键）
-- ARGV[1] = 租约 TTL（秒）
--
-- Returns:
--   {1}  抢占成功：ACTIVE → COMMITTING，租约已写（正常 commit 入口）
--   {2}  会话已 COMMITTED：调用方读 documentId 做确定性幂等回查
--   {3}  COMMITTING 且租约存活：对端正在提交，返回冲突让前端稍后重试
--        （绝不按已提交假成功）
--   {4}  COMMITTING 且租约已过期：进程崩溃残留，接管（重写租约续走）
--        —— 调用方按三分支判定续走（见 DirectUploadServiceImpl#takeoverBranch）
--   {0}  会话不存在 / 已 ABORTED：拒绝提交

local status = redis.call('HGET', KEYS[1], 'status')

if status == false then
    return {0}
end

if status == 'COMMITTED' then
    return {2}
end

if status == 'COMMITTING' then
    if redis.call('EXISTS', KEYS[2]) == 1 then
        return {3}  -- 对端持有租约，冲突
    end
    -- 租约过期：崩溃残留，接管并重写租约
    redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])
    return {4}
end

if status == 'ABORTED' then
    return {0}
end

-- status == 'ACTIVE'：抢占
redis.call('HSET', KEYS[1], 'status', 'COMMITTING')
redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])
return {1}
