-- atomic_chunk_upload.lua
-- 原子操作：记录分片 ETag + 检查是否全部完成 + 设合并锁
--
-- KEYS[1] = upload:parts:{uploadId}
-- ARGV[1] = chunkIndex
-- ARGV[2] = etag
-- ARGV[3] = totalChunks
-- ARGV[4] = __merging field name
--
-- Returns:
--   {1, uploadedCount}  — 所有分片已上传，触发合并
--   {0, uploadedCount}  — 未完成或已在合并中

-- 1. 检查是否已标记合并中（防重入）
local merging = redis.call('HEXISTS', KEYS[1], ARGV[4])
if tonumber(merging) == 1 then
    return {0, -1}  -- 已有其他线程在合并
end

-- 2. 记录分片 ETag（幂等：相同 chunkIndex 会覆盖）
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])

-- 3. 计算已上传分片数（排除元数据字段 __merging）
local len = redis.call('HLEN', KEYS[1])
local metaFields = 0
if redis.call('HEXISTS', KEYS[1], ARGV[4]) == 1 then
    metaFields = metaFields + 1
end
local uploadedCount = len - metaFields

-- 4. 检查是否所有分片已上传
if tonumber(uploadedCount) == tonumber(ARGV[3]) then
    -- 原子设置合并锁
    redis.call('HSET', KEYS[1], ARGV[4], '1')
    return {1, uploadedCount}  -- 触发合并
end

return {0, uploadedCount}  -- 未完成
