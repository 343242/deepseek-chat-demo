package com.demo.deepseekchat.security.service;

import com.demo.deepseekchat.exception.RateLimitExceededException;
import com.demo.deepseekchat.security.dto.CaptchaResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 滑块拼图验证码服务。
 * 纯 Java 2D 实现，无外部图片/模型依赖。
 *
 * 流程：
 * 1. 生成带随机噪点/渐变的背景图 (310×155)
 * 2. 在随机 x 位置抠出拼图块 (47×47)
 * 3. 底图对应位置绘制半透明遮罩
 * 4. captchaId → 正确 x 坐标存入 Caffeine（5 分钟 TTL）
 * 5. 校验时容差 ±5px
 *
 * dev profile 会额外返回 answer，方便 API 测试。
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private static final int BG_WIDTH = 310;
    private static final int BG_HEIGHT = 155;
    private static final int PUZZLE_SIZE = 47;
    private static final int PUZZLE_PADDING = 10;
    private static final int TOLERANCE = 5;
    private static final int CAPTCHA_RATE_LIMIT = 20; // 每分钟每 IP 最多生成 20 次

    private final SecureRandom secureRandom = new SecureRandom();
    private final Cache<String, Integer> captchaCache;
    /**
     * Per-IP rate limit counter: tracks count within a 1-minute sliding window.
     */
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

    /**
     * 生成滑块验证码。
     *
     * @return CaptchaResult 含 captchaId、底图、拼图块、(dev)answer
     */
    public CaptchaResult generate() {
        String captchaId = UUID.randomUUID().toString().replace("-", "");

        // 1. 随机 x 位置
        int answerX = PUZZLE_PADDING + secureRandom.nextInt(BG_WIDTH - PUZZLE_SIZE - 2 * PUZZLE_PADDING);
        int answerY = PUZZLE_PADDING + secureRandom.nextInt(BG_HEIGHT - PUZZLE_SIZE - 2 * PUZZLE_PADDING);

        // 2. 生成背景图
        BufferedImage bgImage = generateBackground();

        // 3. 创建拼图块形状
        Area puzzleShape = createPuzzleShape(answerX, answerY);

        // 4. 从背景抠出拼图块图片
        BufferedImage puzzleImage = extractPuzzlePiece(bgImage, puzzleShape, answerX, answerY);

        // 5. 在背景上绘制凹槽遮罩
        drawSlotOnBackground(bgImage, puzzleShape);

        // 6. 缓存答案
        captchaCache.put(captchaId, answerX);

        // 7. 编码为 Base64
        String bgBase64 = imageToBase64(bgImage);
        String puzzleBase64 = imageToBase64(puzzleImage);

        log.debug("Captcha generated: id={}, answerX={}", captchaId, answerX);

        return new CaptchaResult(
                captchaId,
                bgBase64,
                puzzleBase64,
                exposeAnswer ? answerX : null
        );
    }

    /**
     * 校验滑块验证码。
     *
     * @param captchaId    验证码 ID
     * @param submittedX   用户提交的 x 坐标
     * @return true = 通过
     */
    public boolean validate(String captchaId, int submittedX) {
        if (captchaId == null) return false;

        // 原子取出并删除，防止并发复用同一个 captchaId
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

    // ==================== 图片生成 ====================

    /**
     * 生成带随机渐变 + 噪点的背景图。
     */
    private BufferedImage generateBackground() {
        BufferedImage image = new BufferedImage(BG_WIDTH, BG_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 随机渐变背景
        Color color1 = randomColor(180, 255);
        Color color2 = randomColor(180, 255);
        GradientPaint gradient = new GradientPaint(0, 0, color1, BG_WIDTH, BG_HEIGHT, color2);
        g2d.setPaint(gradient);
        g2d.fillRect(0, 0, BG_WIDTH, BG_HEIGHT);

        // 随机噪点
        for (int i = 0; i < 80; i++) {
            g2d.setColor(randomColor(100, 220));
            int x = secureRandom.nextInt(BG_WIDTH);
            int y = secureRandom.nextInt(BG_HEIGHT);
            g2d.fillOval(x, y, 2 + secureRandom.nextInt(4), 2 + secureRandom.nextInt(4));
        }

        // 随机干扰线
        for (int i = 0; i < 5; i++) {
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

    /**
     * 创建拼图块形状（带凸起/凹陷的经典拼图外形）。
     */
    private Area createPuzzleShape(int x, int y) {
        int s = PUZZLE_SIZE;
        int r = s / 5; // 凸起半径

        GeneralPath path = new GeneralPath();
        // 从左上角开始，顺时针
        path.moveTo(x, y);
        // 上边 + 右侧凸起
        path.lineTo(x + s * 3 / 5, y);
        path.curveTo(x + s * 3 / 5, y - r, x + s * 2 / 5, y - r, x + s * 2 / 5, y);
        path.lineTo(x + s, y);
        // 右边 + 下方凸起
        path.lineTo(x + s, y + s * 3 / 5);
        path.curveTo(x + s + r, y + s * 3 / 5, x + s + r, y + s * 2 / 5, x + s, y + s * 2 / 5);
        path.lineTo(x + s, y + s);
        // 下边
        path.lineTo(x, y + s);
        // 左边
        path.lineTo(x, y);
        path.closePath();

        return new Area(path);
    }

    /**
     * 从背景中抠出拼图块。
     */
    private BufferedImage extractPuzzlePiece(BufferedImage bgImage, Area puzzleShape, int shapeX, int shapeY) {
        // 拼图块图片尺寸比形状大一些，容纳凸起
        int padding = PUZZLE_SIZE / 3;
        int imgW = PUZZLE_SIZE + padding * 2;
        int imgH = PUZZLE_SIZE + padding * 2;
        BufferedImage piece = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g2d = piece.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 平移坐标系，让拼图块在图片中心附近
        g2d.translate(-shapeX + padding, -shapeY + padding);
        g2d.setClip(puzzleShape);
        g2d.drawImage(bgImage, 0, 0, null);

        // 描边
        g2d.setClip(null);
        g2d.translate(shapeX - padding, shapeY - padding);
        g2d.setColor(new Color(255, 255, 255, 180));
        g2d.setStroke(new BasicStroke(2f));
        g2d.draw(puzzleShape);

        g2d.dispose();
        return piece;
    }

    /**
     * 在背景上绘制半透明凹槽遮罩。
     */
    private void drawSlotOnBackground(BufferedImage bgImage, Area puzzleShape) {
        Graphics2D g2d = bgImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 半透明白色填充
        g2d.setColor(new Color(255, 255, 255, 160));
        g2d.fill(puzzleShape);

        // 描边
        g2d.setColor(new Color(200, 200, 200, 200));
        g2d.setStroke(new BasicStroke(1.5f));
        g2d.draw(puzzleShape);

        g2d.dispose();
    }

    /**
     * 检查 IP 验证码生成频率，超限抛异常。
     */
    public void checkRateLimit(String ip) {
        long now = System.currentTimeMillis();
        // Clean up stale entries (older than 60s)
        generationCounter.entrySet().removeIf(e -> now - e.getValue().windowStart > 60_000);

        RateLimitEntry entry = generationCounter.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.windowStart > 60_000) {
                // New window
                return new RateLimitEntry(now);
            }
            // Same window, increment
            existing.count++;
            return existing;
        });

        if (entry.count > CAPTCHA_RATE_LIMIT) {
            throw new RateLimitExceededException("验证码生成过于频繁，请稍后再试");
        }
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
