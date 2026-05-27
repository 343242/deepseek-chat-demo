package com.smart.rag.rag.service;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档去重服务 -- Redisson BloomFilter 预筛
 * <p>
 * 使用 RBloomFilter 快速判断文件 MD5 可能已入库。
 * BloomFilter 存在假阳性，命中后必须再查 DB 确认。
 * BloomFilter 不存在假阴性，未命中则确定不存在。
 * <p>
 * 生命周期：应用启动时从 DB 初始化（全量加载已有 fileMd5），
 * 新文档入库后 add，文档删除后不删除（假阳性可接受，假阴性不可接受）。
 */
@Service
@ConditionalOnBean(RedissonClient.class)
public class DocumentDedupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDedupService.class);

    private static final String BLOOM_FILTER_NAME = "smart-rag:dedup:file-md5";
    /** 预期容量：10 万文档 */
    private static final long EXPECTED_CAPACITY = 100_000;
    /** 误判率 1% */
    private static final double FALSE_PROBABILITY = 0.01;

    private final RBloomFilter<String> bloomFilter;
    private final RagDocumentMapper documentMapper;

    public DocumentDedupService(RedissonClient redissonClient, RagDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
        this.bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);

        // tryInit 只在首次创建时初始化，已存在则跳过
        if (bloomFilter.tryInit(EXPECTED_CAPACITY, FALSE_PROBABILITY)) {
            log.info("BloomFilter initialized: name={}, capacity={}, falseProbability={}",
                BLOOM_FILTER_NAME, EXPECTED_CAPACITY, FALSE_PROBABILITY);
            // 首次初始化时从 DB 加载已有数据
            loadExistingFileMd5s();
        } else {
            log.info("BloomFilter already exists: name={}, size={}",
                BLOOM_FILTER_NAME, bloomFilter.count());
        }
    }

    /**
     * 预筛：文件 MD5 是否可能已入库
     *
     * @param fileMd5 文件 MD5 哈希
     * @return true=可能存在（需查 DB 确认），false=确定不存在
     */
    public boolean mayExist(String fileMd5) {
        return bloomFilter.contains(fileMd5);
    }

    /**
     * 确认文档是否真实存在（BloomFilter 命中后的 DB 确认）
     *
     * @param fileMd5 文件 MD5
     * @param userId  用户 ID（租户隔离）
     * @return 已存在的文档，或 null
     */
    public RagDocument confirmExisting(String fileMd5, Long userId) {
        return documentMapper.selectOne(
            new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getFileMd5, fileMd5)
                .eq(RagDocument::getUserId, userId)
                .in(RagDocument::getStatus,
                    com.smart.rag.rag.etl.EtlStatus.COMPLETED,
                    com.smart.rag.rag.etl.EtlStatus.PROCESSING)
                .eq(RagDocument::getDeleted, 0)
                .last("LIMIT 1")
        );
    }

    /**
     * 新文档入库后添加到 BloomFilter
     */
    public void add(String fileMd5) {
        bloomFilter.add(fileMd5);
    }

    private void loadExistingFileMd5s() {
        List<RagDocument> docs = documentMapper.selectList(
            new LambdaQueryWrapper<RagDocument>()
                .select(RagDocument::getFileMd5)
                .eq(RagDocument::getDeleted, 0)
                .isNotNull(RagDocument::getFileMd5)
        );
        int count = 0;
        for (RagDocument doc : docs) {
            if (doc.getFileMd5() != null && bloomFilter.add(doc.getFileMd5())) {
                count++;
            }
        }
        log.info("BloomFilter loaded {} existing file MD5s from DB", count);
    }
}
