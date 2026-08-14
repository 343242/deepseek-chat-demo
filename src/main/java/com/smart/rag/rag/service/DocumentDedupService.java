package com.smart.rag.rag.service;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 文档去重服务 -- Redisson BloomFilter 预筛
 * <p>
 * 使用 RBloomFilter 快速判断文件校验和（SHA-256）可能已入库。
 * BloomFilter 存在假阳性，命中后必须再查 DB 确认。
 * BloomFilter 不存在假阴性，未命中则确定不存在。
 * <p>
 * 生命周期：构造器只做 {@code tryInit}（轻量），
 * 全量校验和加载移到 {@link #warmUp()} 监听 {@link ApplicationReadyEvent} 异步执行（R1-M6），
 * 不再阻塞应用启动；warm 失败仅记录日志、降级到 DB 去重。
 * 新文档入库后 add，文档删除后不删除（假阳性可接受，假阴性不可接受）。
 * <p>
 * 当 RedissonClient 不可用时（如 Redis 未连接），bloomFilter 为 null，
 * mayExist() 始终返回 false（不拦截），退化为纯 DB 去重。
 * <p>
 * 冷启动安全：在 warmUp 完成前 mayExist() 始终返回 true，
 * 调用方因此始终走到 DB 确认路径（confirmExisting）——
 * 即降级为纯 DB 去重，无假阴性风险。
 */
@Service
public class DocumentDedupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDedupService.class);

    private static final String BLOOM_FILTER_NAME = "smart-rag:dedup:file-checksum";
    // NOTE: 预期容量/误判率为部署级常量（无对应 properties 类，不为此新建配置管道）。
    // 注意 tryInit 参数只在 Redis 中 BloomFilter 首次创建时生效，改小值需重建过滤器才有效。
    /** 预期容量：10 万文档 */
    private static final long EXPECTED_CAPACITY = 100_000;
    /** 误判率 1% */
    private static final double FALSE_PROBABILITY = 0.01;
    /** R1-M6: 全量加载的分页大小，避免单次 selectList 在大表上 OOM */
    private static final int WARMUP_BATCH_SIZE = 5_000;

    private final @Nullable RBloomFilter<String> bloomFilter;
    private final RagDocumentMapper documentMapper;

    /**
     * R1-M6: warm-up 完成标志。
     * true=全量校验和已加载，mayExist() 走 BloomFilter；
     * false=冷启动中，mayExist() 始终返回 true（强制走 DB 确认路径）。
     */
    private volatile boolean warmedUp = false;

    public DocumentDedupService(@Nullable RedissonClient redissonClient, RagDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
        if (redissonClient != null) {
            this.bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
            // tryInit 只在首次创建时初始化结构，不触发 DB 全量加载（R1-M6）
            if (bloomFilter.tryInit(EXPECTED_CAPACITY, FALSE_PROBABILITY)) {
                log.info("BloomFilter initialized: name={}, capacity={}, falseProbability={}",
                    BLOOM_FILTER_NAME, EXPECTED_CAPACITY, FALSE_PROBABILITY);
            } else {
                log.info("BloomFilter already exists: name={}, size={}",
                    BLOOM_FILTER_NAME, bloomFilter.count());
            }
        } else {
            this.bloomFilter = null;
            log.warn("RedissonClient not available, BloomFilter dedup disabled");
        }
    }

    /**
     * R1-M6: 应用就绪后异步 warm-up，从 DB 分页加载已存在的 fileChecksum。
     * <p>
     * 失败处理：任何异常都被吞掉，仅记录 error 日志。warmedUp 保持 false，
     * mayExist() 继续返回 true，系统降级为纯 DB 去重，不影响应用可用性。
     * <p>
     * 异步执行于 etlIoExecutor（与 ETL IO 任务共用线程池），
     * 不阻塞 ApplicationReadyEvent 的同步处理。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async("etlIoExecutor")
    public void warmUp() {
        if (bloomFilter == null) {
            // LOW-4: BloomFilter 不可用是稳定状态（非"正在 warm"）。标记 warmedUp=true，
            // 避免运维看到 false 误以为 warm 进行中；mayExist() 已对 bloomFilter==null 单独 return true。
            warmedUp = true;
            log.info("BloomFilter disabled (RedissonClient unavailable); dedup falls back to DB-only. warmedUp={}", warmedUp);
            return;
        }
        long started = System.currentTimeMillis();
        try {
            long totalAdded = loadExistingFileChecksumsBatched();
            warmedUp = true;
            log.info("BloomFilter warm-up done: {} checksums loaded in {}ms, warmedUp={}",
                    totalAdded, System.currentTimeMillis() - started, warmedUp);
        } catch (Exception e) {
            // 关键：warm-up 失败绝不阻止应用运行 —— 降级到 DB 去重
            log.error("BloomFilter warm-up failed, degrading to DB-only dedup. mayExist() will return true.", e);
        }
    }

    /**
     * 预筛：文件校验和是否可能已入库
     *
     * @param fileChecksum 文件校验和（SHA-256）
     * @return true=可能存在（需查 DB 确认），false=确定不存在
     *         <p>
     *         冷启动语义（R1-M6）：warmedUp=false 时始终返回 true，
     *         调用方因此走 confirmExisting 的 DB 确认路径，
     *         避免未加载完时误判「不存在」造成重复入库（假阴性）。
     */
    public boolean mayExist(String fileChecksum) {
        if (bloomFilter == null) {
            // BloomFilter 不可用：不拦截，让调用方走 DB 确认
            return true;
        }
        if (!warmedUp) {
            // 冷启动：未加载完，保守返回 true 走 DB 确认，避免假阴性
            return true;
        }
        return bloomFilter.contains(fileChecksum);
    }

    /**
     * 确认文档是否真实存在（BloomFilter 命中后的 DB 确认）
     *
     * @param fileChecksum 文件校验和（SHA-256）
     * @param userId  用户 ID（租户隔离）
     * @param teamId  团队 ID（null = 个人空间，非 null = 团队空间）
     * @return 已存在的文档，或 null
     */
    public RagDocument confirmExisting(String fileChecksum, Long userId, @Nullable Long teamId) {
        LambdaQueryWrapper<RagDocument> wrapper = new LambdaQueryWrapper<RagDocument>()
            .eq(RagDocument::getFileChecksum, fileChecksum)
            .in(RagDocument::getStatus,
                com.smart.rag.rag.etl.EtlStatus.COMPLETED,
                com.smart.rag.rag.etl.EtlStatus.PROCESSING)
            .eq(RagDocument::getDeleted, 0);
        if (teamId != null) {
            wrapper.eq(RagDocument::getTeamId, teamId);
        } else {
            wrapper.eq(RagDocument::getUserId, userId)
                   .isNull(RagDocument::getTeamId);
        }
        return documentMapper.selectOne(wrapper.last("LIMIT 1"));
    }

    /**
     * 新文档入库后添加到 BloomFilter
     */
    public void add(String fileChecksum) {
        if (bloomFilter != null) {
            bloomFilter.add(fileChecksum);
        }
    }

    /**
     * R1-M6: warm-up 状态查询，主要用于测试与运维观测。
     */
    public boolean isWarmedUp() {
        return warmedUp;
    }

    /**
     * R1-M6: 分页加载已存在的 fileChecksum，避免在大表上一次 selectList 拖垮内存。
     * 按 id 升序分页，直到某页返回不满（< WARMUP_BATCH_SIZE）即终止。
     * <p>
     * protected 以支持测试跨包覆写（替代反射）—— 非公共扩展点。
     */
    protected long loadExistingFileChecksumsBatched() {
        long totalAdded = 0;
        long lastId = 0L;
        while (true) {
            final long lowerBound = lastId;
            // LOW-3: searchCount=false —— warm-up 只需遍历全部页数据，跳过 COUNT 查询（全表 COUNT 昂贵且此处无用）
            Page<RagDocument> pageReq = new Page<>(1, WARMUP_BATCH_SIZE, false);
            LambdaQueryWrapper<RagDocument> wrapper = new LambdaQueryWrapper<RagDocument>()
                    .select(RagDocument::getId, RagDocument::getFileChecksum)
                    .eq(RagDocument::getDeleted, 0)
                    .isNotNull(RagDocument::getFileChecksum)
                    .gt(RagDocument::getId, lowerBound)
                    .orderByAsc(RagDocument::getId);
            IPage<RagDocument> pageRes = documentMapper.selectPage(pageReq, wrapper);
            var records = pageRes.getRecords();
            if (records.isEmpty()) {
                break;
            }
            for (RagDocument doc : records) {
                if (doc.getFileChecksum() != null && bloomFilter.add(doc.getFileChecksum())) {
                    totalAdded++;
                }
                lastId = doc.getId();
            }
            if (records.size() < WARMUP_BATCH_SIZE) {
                break;
            }
        }
        return totalAdded;
    }
}
