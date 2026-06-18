package com.smart.rag.infrastructure.web.service;

import com.smart.rag.infrastructure.exception.RateLimitExceededException;
import com.smart.rag.infrastructure.web.dto.CaptchaResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 滑块拼图验证码服务 —— 预生成池化版本。
 * <p>
 * 性能优化：后台线程预生成验证码放入 {@link #pool}，请求时 O(1) 拿取，
 * 避免在请求线程上执行 CPU 密集的 Java 2D 图片生成 + Base64 编码。
 * </p>
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private static final int BG_WIDTH = 310;
    private static final int BG_HEIGHT = 155;
    private static final int PUZZLE_SIZE = 47;
    private static final int PUZZLE_PADDING = 10;
    private static final int TOLERANCE = 5;
    private static final int CAPTCHA_RATE_LIMIT = 20;

    /** 预生成池目标大小 */
    private static final int POOL_TARGET = 50;
    /** 池水位低于此值时触发异步补充 */
    private static final int POOL_LOW_WATERMARK = 20;
    /** 单次批量补充数量 */
    private static final int POOL_BATCH_SIZE = 30;
    /** 池最大容量，防止 OOM */
    private static final int POOL_MAX = 200;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Cache<String, Integer> captchaCache;

    /** 预生成的验证码 */
    private record PreGenerated(String captchaId, String bgBase64, String puzzleBase64, int answerX) {}

    /** 预生成池：LinkedBlockingDeque 支持超时 poll，线程安全 */
    private final LinkedBlockingDeque<PreGenerated> pool = new LinkedBlockingDeque<>(POOL_MAX);

    /** 后台补充线程池（单线程足够） */
    private ScheduledExecutorService scheduler;

    private static final class RateLimitEntry {
        long windowStart;
        int count;
        RateLimitEntry(long windowStart) {
            this.windowStart = windowStart;
            this.count = 1;
        }
    }
    private final ConcurrentHashMap<String, RateLimitEntry> generationCounter = new ConcurrentHashMap<>();
    private final boolean exposeAnswer;

    public CaptchaService(@Value("${app.captcha.expose-answer:false}") boolean exposeAnswer) {
        this.captchaCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
        this.exposeAnswer = exposeAnswer;
    }

    @PostConstruct
    void init() {
        // 启动时在后台线程预填充池，不阻塞 Spring 启动
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "captcha-pool-filler");
            t.setDaemon(true);
            return t;
        });
        scheduler.execute(this::fillPool);
        // 定时检查池水位，每 10 秒补充一次
        scheduler.scheduleWithFixedDelay(this::replenishIfNeeded, 10, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 生成滑块验证码 —— 优先从预生成池拿取，O(1)。
     */
    public CaptchaResult generate() {
        PreGenerated pre = pool.poll();
        if (pre != null) {
            // 池中拿到，注册到 captchaCache（池中预生成时还没有 5 分钟 TTL）
            captchaCache.put(pre.captchaId(), pre.answerX());
            log.debug("Captcha served from pool: id={}, remaining={}", pre.captchaId(), pool.size());
            return toResult(pre);
        }

        // 池耗尽，降级为同步生成（保证可用性）
        log.warn("Captcha pool exhausted, generating synchronously");
        return generateSync();
    }

    /**
     * 校验滑块验证码。
     */
    public boolean validate(String captchaId, int submittedX) {
        if (captchaId == null) return false;
        Integer answerX = captchaCache.asMap().remove(captchaId);
        if (answerX == null) {
            log.debug("Captcha not found or expired: {}", captchaId);
            return false;
        }
        boolean passed = Math.abs(submittedX - answerX) <= TOLERANCE;
        log.debug("Captcha validation: id={}, submitted={}, answer={}, passed={}",
                captchaId, submittedX, answerX, passed);
        return passed;
    }

    /**
     * 检查 IP 验证码生成频率。
     */
    public void checkRateLimit(String ip) {
        long now = System.currentTimeMillis();
        generationCounter.entrySet().removeIf(e -> now - e.getValue().windowStart > 60_000);
        RateLimitEntry entry = generationCounter.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.windowStart > 60_000) {
                return new RateLimitEntry(now);
            }
            existing.count++;
            return existing;
        });
        if (entry.count > CAPTCHA_RATE_LIMIT) {
            throw new RateLimitExceededException("验证码生成过于频繁，请稍后再试");
        }
    }

    // ==================== 池管理 ====================

    private void replenishIfNeeded() {
        int currentSize = pool.size();
        if (currentSize < POOL_LOW_WATERMARK) {
            log.debug("Captcha pool low ({}/{}), replenishing...", currentSize, POOL_TARGET);
            fillPool();
        }
    }

    /** 将池填充到 POOL_TARGET 个 */
    private void fillPool() {
        int toFill = POOL_TARGET - pool.size();
        if (toFill <= 0) return;

        int generated = 0;
        for (int i = 0; i < toFill; i++) {
            try {
                PreGenerated pre = generatePre();
                if (!pool.offer(pre)) {
                    break; // 池满
                }
                generated++;
            } catch (Exception e) {
                log.warn("Failed to pre-generate captcha: {}", e.getMessage());
            }
        }
        log.debug("Captcha pool replenished: +{}, total={}", generated, pool.size());
    }

    /** 预生成一个验证码（不写入 captchaCache，等到实际消费时再写入，避免过期浪费） */
    private PreGenerated generatePre() {
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        int answerX = PUZZLE_PADDING + secureRandom.nextInt(BG_WIDTH - PUZZLE_SIZE - 2 * PUZZLE_PADDING);
        int answerY = PUZZLE_PADDING + secureRandom.nextInt(BG_HEIGHT - PUZZLE_SIZE - 2 * PUZZLE_PADDING);

        BufferedImage bgImage = generateBackground();
        Area puzzleShape = createPuzzleShape(answerX, answerY);
        BufferedImage puzzleImage = extractPuzzlePiece(bgImage, puzzleShape, answerX, answerY);
        drawSlotOnBackground(bgImage, puzzleShape);

        String bgBase64 = imageToBase64(bgImage);
        String puzzleBase64 = imageToBase64(puzzleImage);

        return new PreGenerated(captchaId, bgBase64, puzzleBase64, answerX);
    }

    /** 池耗尽时的降级：同步生成 */
    private CaptchaResult generateSync() {
        PreGenerated pre = generatePre();
        captchaCache.put(pre.captchaId(), pre.answerX());
        return toResult(pre);
    }

    private CaptchaResult toResult(PreGenerated pre) {
        return new CaptchaResult(
                pre.captchaId(),
                pre.bgBase64(),
                pre.puzzleBase64(),
                exposeAnswer ? pre.answerX() : null
        );
    }

    // ==================== 图片生成 ====================

    private BufferedImage generateBackground() {
        BufferedImage image = new BufferedImage(BG_WIDTH, BG_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color color1 = randomColor(180, 255);
        Color color2 = randomColor(180, 255);
        GradientPaint gradient = new GradientPaint(0, 0, color1, BG_WIDTH, BG_HEIGHT, color2);
        g2d.setPaint(gradient);
        g2d.fillRect(0, 0, BG_WIDTH, BG_HEIGHT);

        // 随机噪点（减少到 50 个，视觉差异微乎其微）
        for (int i = 0; i < 50; i++) {
            g2d.setColor(randomColor(100, 220));
            int x = secureRandom.nextInt(BG_WIDTH);
            int y = secureRandom.nextInt(BG_HEIGHT);
            g2d.fillOval(x, y, 2 + secureRandom.nextInt(4), 2 + secureRandom.nextInt(4));
        }

        // 随机干扰线（减少到 3 条）
        for (int i = 0; i < 3; i++) {
            g2d.setColor(randomColor(120, 200));
            g2d.setStroke(new BasicStroke(1 + secureRandom.nextFloat()));
            int x1 = secureRandom.nextInt(BG_WIDTH);
            int y1 = secureRandom.nextInt(BG_HEIGHT);
            int x2 = secureRandom.nextInt(BG_WIDTH);
            int y2 = secureRandom.nextInt(BG_HEIGHT);
            g2d.drawLine(x1, y1, x2, y2);
        }

        g2d.dispose();
        return image;
    }

    private Area createPuzzleShape(int x, int y) {
        int s = PUZZLE_SIZE;
        int r = s / 5;
        GeneralPath path = new GeneralPath();
        path.moveTo(x, y);
        path.lineTo(x + s * 3 / 5, y);
        path.curveTo(x + s * 3 / 5, y - r, x + s * 2 / 5, y - r, x + s * 2 / 5, y);
        path.lineTo(x + s, y);
        path.lineTo(x + s, y + s * 3 / 5);
        path.curveTo(x + s + r, y + s * 3 / 5, x + s + r, y + s * 2 / 5, x + s, y + s * 2 / 5);
        path.lineTo(x + s, y + s);
        path.lineTo(x, y + s);
        path.lineTo(x, y);
        path.closePath();
        return new Area(path);
    }

    private BufferedImage extractPuzzlePiece(BufferedImage bgImage, Area puzzleShape, int shapeX, int shapeY) {
        int padding = PUZZLE_SIZE / 3;
        int imgW = PUZZLE_SIZE + padding * 2;
        int imgH = PUZZLE_SIZE + padding * 2;
        BufferedImage piece = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = piece.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.translate(-shapeX + padding, -shapeY + padding);
        g2d.setClip(puzzleShape);
        g2d.drawImage(bgImage, 0, 0, null);
        g2d.setClip(null);
        g2d.translate(shapeX - padding, shapeY - padding);
        g2d.setColor(new Color(255, 255, 255, 180));
        g2d.setStroke(new BasicStroke(2f));
        g2d.draw(puzzleShape);
        g2d.dispose();
        return piece;
    }

    private void drawSlotOnBackground(BufferedImage bgImage, Area puzzleShape) {
        Graphics2D g2d = bgImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(new Color(255, 255, 255, 160));
        g2d.fill(puzzleShape);
        g2d.setColor(new Color(200, 200, 200, 200));
        g2d.setStroke(new BasicStroke(1.5f));
        g2d.draw(puzzleShape);
        g2d.dispose();
    }

    private String imageToBase64(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode captcha image", e);
        }
    }

    private Color randomColor(int min, int max) {
        return new Color(
                min + secureRandom.nextInt(max - min),
                min + secureRandom.nextInt(max - min),
                min + secureRandom.nextInt(max - min)
        );
    }
}
