package com.smart.rag.rag.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.MessageConsumeException;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.ConsumerMode;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.config.ImageConsumerProperties;
import com.smart.rag.rag.entity.DocumentImage;
import com.smart.rag.rag.mapper.DocumentImageMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.parser.odl.OdlConfigs;
import com.smart.rag.rag.parser.odl.OdlResourceCleaner;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.ObjectReadRange;
import com.smart.rag.rag.service.StoredObjectContent;
import com.smart.rag.rag.service.StoredObjectHandle;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.verapdf.wcag.algorithms.entities.ObjectKey;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.ImagesUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 图片提取后台消费者（design §6.4）——模式对齐 {@link EtlDocumentConsumer}。
 * <p>
 * 线程模型（v1.6 中-3）：不新增自建线程池——线程由 RedisStreamConsumerRunner 提供
 * （SIMPLE：redis-receive-{topic} 单线程拉取 + redis-process-{topic}-N 处理池），
 * {@code odlImageConcurrency} 1:1 映射 {@code ConsumerConfig.concurrency}
 * （处理池线程数 = in-flight 许可数 = ODL 渲染内存并发上界）。
 * <p>
 * <b>ack/nack 契约映射（v1.7 高-1，平台实态）</b>：nack 的实际机制是 handle 的可重试
 * 白名单（封闭集合）——本消费者所有预期瞬时失败一律抛 {@link MessageConsumeException}
 * （白名单内）：XACK + RetrySweeper ZSET 延迟回灌，BackoffSchedule 16 档，预算 =
 * {@code redis.max-attempts}（默认 16），耗尽写 DLQ；白名单外异常首错直接 DLQ。
 * 严禁新建白名单外的重试异常类型。{@link GenerationInvalidException} 严格内部捕获
 * 不逃逸。
 * <p>
 * 消费幂等 = 行级条件更新（{@code WHERE status='PENDING'}）；消息不设 dedupKey
 * （v1.6 严重-2：SETNX 先标记后执行，崩溃窗口重投会被判重静默 ACK）。
 */
@Component
public class ImageExtractConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractConsumer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImageConsumerProperties consumerProperties;
    private final DocumentProperties documentProperties;
    private final MessageBus messageBus;
    private final RedissonClient redisson;
    private final DocumentImageMapper documentImageMapper;
    private final RagDocumentMapper ragDocumentMapper;
    private final FileStorageService fileStorageService;
    private final ImageCleanupService imageCleanupService;
    private final ImageMetrics imageMetrics;

    private volatile Subscription subscription;
    private volatile boolean running;

    public ImageExtractConsumer(ImageConsumerProperties consumerProperties,
                                DocumentProperties documentProperties,
                                MessageBus messageBus,
                                RedissonClient redisson,
                                DocumentImageMapper documentImageMapper,
                                RagDocumentMapper ragDocumentMapper,
                                FileStorageService fileStorageService,
                                ImageCleanupService imageCleanupService,
                                ImageMetrics imageMetrics) {
        this.consumerProperties = consumerProperties;
        this.documentProperties = documentProperties;
        this.messageBus = messageBus;
        this.redisson = redisson;
        this.documentImageMapper = documentImageMapper;
        this.ragDocumentMapper = ragDocumentMapper;
        this.fileStorageService = fileStorageService;
        this.imageCleanupService = imageCleanupService;
        this.imageMetrics = imageMetrics;
    }

    @Override
    public void start() {
        if (running) return;

        ConsumerConfig consumerConfig = ConsumerConfig.builder()
                .consumerMode(ConsumerMode.SIMPLE)
                .batchSize(consumerProperties.getBatchSize())
                .invisibleDuration(consumerProperties.getInvisibleDuration())
                // 中-3：odlImageConcurrency 1:1 映射（处理池线程数 = in-flight 许可数）
                .concurrency(Math.max(1, documentProperties.getOdlImageConcurrency()))
                .build();

        String topic = consumerProperties.getTopic();
        subscription = messageBus.subscribe(topic, consumerProperties.getGroup(),
                consumerConfig, ImageExtractJob.class, msg -> consume(msg.payload()));
        running = true;
        log.info("Image extract consumer started: topic={}, group={}, concurrency={}",
                topic, consumerProperties.getGroup(), documentProperties.getOdlImageConcurrency());
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        log.info("Image extract consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE - 100;
    }

    // ==================== 消费主体 ====================

    /** 代际失效（高-2）：行条件更新 0 行 = 前台已重建清单，中止本批。严格内部捕获不逃逸。 */
    static final class GenerationInvalidException extends RuntimeException {
        GenerationInvalidException(String message) {
            super(message);
        }
    }

    void consume(ImageExtractJob job) {
        long start = System.nanoTime();
        RLock lock = null;
        boolean locked = false;
        List<DocumentImage> rows = List.of();
        Path tempPdf = null;
        try {
            // 中-8b：锁获取也置于 try 内，tryAcquire 与 try 之间不留可抛间隙；
            // 获取失败 → MessageConsumeException（白名单内）驱动重投
            lock = redisson.getLock("smart-rag:image:lock:" + job.documentId());
            locked = lock.tryLock(30, -1, TimeUnit.SECONDS);
            if (!locked) {
                throw new MessageConsumeException("document-lock-contended: " + job.documentId(), null);
            }

            // 严重-2a：SUPERSEDED 视同已删除（新版本=新 documentId，旧文件已清理，
            // existsById 会误放行 → 下载 404 烧预算误报 dead）
            if (ragDocumentMapper.countProcessable(job.documentId()) == 0) {
                imageCleanupService.cleanupByDocumentId(job.documentId());
                return;   // 终态文档：清行清对象退出 → 正常返回 = ack
            }
            rows = documentImageMapper.findPending(job.documentId());   // 中-6：ORDER BY page_number, seq
            if (rows.isEmpty()) {
                return;   // 双投递/旧消息：行已终态 → ack
            }

            tempPdf = downloadToTemp(job);

            Config cfg = OdlConfigs.background();     // 中-8a：统一门面（含 H-C1 fail-fast）
            DocumentProcessor.preprocessing(tempPdf.toString(), cfg);
            ImagesUtils utils = StaticContainers.getImagesUtils();   // M7：懒实例，清理镜像第 2 步关闭
            if (utils == null) {
                // 低-2：懒创建失败归类瞬时（资源压力）；粘性标志被清理镜像重置，无永久循环
                throw new MessageConsumeException("images-utils-init-failed: " + job.documentId(), null);
            }

            int lastPage = -1;
            for (DocumentImage row : rows) {
                if (row.getPageNumber() != lastPage) {
                    utils.clearRenderedPages();   // H2：换页显式清缓存（内存上界 = 1 页）
                    lastPage = row.getPageNumber();
                }
                if (row.getSeq() != null && row.getSeq() > documentProperties.getOdlImageMaxPerDoc()) {
                    markSkipped(row, "doc-image-budget");
                    continue;
                }
                BufferedImage img;
                try {
                    img = fetchImage(utils, row);
                } catch (Exception e) {
                    if (isDecodeUnsupported(e)) {
                        markSkipped(row, "decode-unsupported");   // 低-3：结构性终态，不入重放
                        continue;
                    }
                    throw e;   // 未知异常默认瞬时（宁可多耗一次重试，不误判死刑）
                }
                if (img == null) {
                    markSkipped(row, "unresolvable-xobject");
                    continue;
                }
                // 低-4：解码后像素复核（兜底：字典预判不可得时仍拦得住）
                if ((long) img.getWidth() * img.getHeight() * 3 > documentProperties.getOdlImageMaxBytes()) {
                    markSkipped(row, "max-bytes-exceeded");
                    continue;
                }
                byte[] bytes = encode(img, extOf(row));
                if (bytes.length > documentProperties.getOdlImageMaxBytes()) {
                    markSkipped(row, "max-bytes-exceeded");
                    continue;
                }
                fileStorageService.upload(bucketOf(job), row.getStorageKey(),
                        new ByteArrayResource(bytes), mimeOf(row));
                // 高-2：条件更新——0 行 = 代际失效，立即中止本批
                if (documentImageMapper.markUploadedConditionally(row.getId(), (long) bytes.length) == 0) {
                    throw new GenerationInvalidException("manifest-rebuilt-during-consume");
                }
            }
        } catch (GenerationInvalidException e) {
            // 高-2：中止本批，旧行已被前台重建事务删除无需 reset；跨代对象归对账清理。
            // 中-2：ACK 前复查——新清单 PENDING>0 且驱动消息可能已丢（高-1 窗口）时
            // 抛重试让本消息重投接管，而非静默 ACK
            if (documentImageMapper.countPending(job.documentId()) > 0) {
                throw new MessageConsumeException("generation-invalid-but-pending-remains: "
                        + job.documentId(), e);
            }
            log.info("Image generation invalid (new manifest empty or driven elsewhere): doc={}",
                    job.documentId());
        } catch (MessageConsumeException e) {
            resetUnfinishedToPending(rows, e.getMessage());
            throw e;
        } catch (Exception e) {
            // M6 + 中-1：瞬时 → 仅非终态行回置（终态不可回退），异常驱动重投（白名单内）
            String reason = sanitize(e);
            resetUnfinishedToPending(rows, reason);
            throw new MessageConsumeException("image-extract-failed: " + job.documentId()
                    + ": " + reason, e);
        } finally {
            if (tempPdf != null) {
                OdlResourceCleaner.cleanupMirror();   // 消费线程也必须执行九步镜像
                safeDelete(tempPdf);
            }
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            imageMetrics.consume(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private BufferedImage fetchImage(ImagesUtils utils, DocumentImage row) throws IOException {
        if ("PAGE_RENDER".equals(row.getImgType())) {
            // bbox round-trip（EXP7 验证）；alpha 统一压平白底（v1.3 高-2：ext 确定性）
            return utils.getPageSubImage(bboxOf(row), (double) documentProperties.getOdlImageRenderDpi());
        }
        return utils.getXObjectImage(row.getPageNumber(),
                new ObjectKey(row.getObjectNum(), row.getObjectGen()));
    }

    private BoundingBox bboxOf(DocumentImage row) {
        try {
            double[] b = JSON.readValue(row.getBbox(), double[].class);
            return new BoundingBox(b);
        } catch (Exception e) {
            throw new IllegalStateException("bbox parse failed for row " + row.getId(), e);
        }
    }

    /** 低-3 分类器：JPEG2000/CMYK/JBIG2 等确定性解码不支持 → 结构性终态。白名单式匹配。 */
    private static boolean isDecodeUnsupported(Throwable e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("jpx") || msg.contains("jpeg2000") || msg.contains("jpeg 2000")
                || msg.contains("jbig2") || msg.contains("cmyk")
                || e instanceof javax.imageio.IIOException;
    }

    private void markSkipped(DocumentImage row, String reason) {
        documentImageMapper.markSkippedConditionally(row.getId(), reason);
        imageMetrics.skipped(reason).increment();
        log.warn("Image row skipped: doc={}, seq={}, reason={}", row.getDocumentId(), row.getSeq(), reason);
    }

    /** 中-1：仅 PENDING 行回置（终态 UPLOADED/SKIPPED/FAILED 不可回退） */
    private void resetUnfinishedToPending(List<DocumentImage> rows, String reason) {
        List<Long> ids = new ArrayList<>();
        for (DocumentImage row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        if (!ids.isEmpty()) {
            try {
                documentImageMapper.resetPendingByIds(ids, truncate(reason, 500));
            } catch (Exception e) {
                log.error("Failed to reset rows to PENDING: {}", ids, e);
            }
        }
    }

    // ==================== 编码 ====================

    private static String extOf(DocumentImage row) {
        return "PAGE_RENDER".equals(row.getImgType()) ? "jpeg" : "png";
    }

    private static String mimeOf(DocumentImage row) {
        return "PAGE_RENDER".equals(row.getImgType()) ? "image/jpeg" : "image/png";
    }

    private static String bucketOf(ImageExtractJob job) {
        return job.bucket();
    }

    /** 当页编码后即弃（getSubimage 共享父光栅——不得跨页持有引用，§3.4） */
    private byte[] encode(BufferedImage img, String ext) {
        try (var bos = new ByteArrayOutputStream()) {
            if ("jpeg".equals(ext)) {
                writeJpeg(flattenToWhite(img), bos, documentProperties.getOdlImageJpegQuality());
            } else {
                ImageIO.write(img, "png", bos);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("image encode failed: " + ext, e);
        }
    }

    /** alpha 压平白底（页面渲染本无透明语义） */
    private static BufferedImage flattenToWhite(BufferedImage src) {
        if (!src.getColorModel().hasAlpha()) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void writeJpeg(BufferedImage img, ByteArrayOutputStream out, double quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(img, "jpg", out);
            return;
        }
        ImageWriter writer = writers.next();
        try (var ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality((float) Math.min(1.0, Math.max(0.1, quality)));
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ==================== 下载 ====================

    /**
     * 低-5② 契约：自身失败时清理已建的部分文件再抛出（finally 的 tempPdf!=null 门
     * 只负责成功路径，半途失败窗口由本方法内部 try/finally 封闭）。
     */
    private Path downloadToTemp(ImageExtractJob job) {
        Path temp = null;
        try {
            temp = Files.createTempFile("rag-image-", ".pdf");
            StoredObjectHandle handle = fileStorageService.open(job.bucket(), job.objectKey());
            StoredObjectContent content = handle.content(new ObjectReadRange.Full());
            try (InputStream in = content.resource().getInputStream();
                 var out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            return temp;
        } catch (Exception e) {
            if (temp != null) {
                safeDelete(temp);
            }
            throw new IllegalStateException("download-to-temp failed: " + job.objectKey(), e);
        }
    }

    private static void safeDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", path, e);
        }
    }

    private static String sanitize(Throwable e) {
        return truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
