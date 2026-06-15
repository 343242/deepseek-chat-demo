package com.smart.rag.rag.config;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OOXML 解压炸弹防御配置（R2-H2）。
 * <p>
 * POI 5.5.1 的 {@link ZipSecureFile} 默认阈值已生效，但默认值可能被
 * 依赖变更、JVM 系统属性（{@code -Dpoi.*}）或反射静默削弱。本类在应用启动时
 * 显式钉死阈值并断言回读一致，使任何削弱都尽早暴露为启动失败而非静默降级。
 * <p>
 * 阈值取自 POI 文档的安全默认值：
 * <ul>
 *   <li>{@code minInflateRatio = 0.01} —— 压缩比低于 1% 视为 zip-bomb（POI 默认 0.01）</li>
 *   <li>{@code maxEntrySize = 4_294_967_295L}（~4GB）—— 单个解压条目上限（POI 默认 0xFFFFFFFF）</li>
 *   <li>{@code maxTextSize = 10_000_000L}（10MB）—— 单条目文本抽取上限（POI 默认 1e7）</li>
 * </ul>
 * <p>
 * 与 {@code DocumentProperties.maxFileSize}（50MB）正交：前者约束解压过程，后者约束输入大小。
 */
@Component
public class ZipSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ZipSecurityConfig.class);

    /** 压缩比下限：解压后/压缩前低于此比例视为 zip-bomb。 */
    private static final double SECURE_MIN_INFLATE_RATIO = 0.01;

    /** 单个 zip entry 解压后最大字节数（~4GB，POI 默认）。 */
    private static final long SECURE_MAX_ENTRY_SIZE = 4_294_967_295L;

    /** 单个 zip entry 文本抽取最大字符数（10MB，POI 默认）。 */
    private static final long SECURE_MAX_TEXT_SIZE = 10_000_000L;

    @PostConstruct
    void pinZipSecurityDefaults() {
        ZipSecureFile.setMinInflateRatio(SECURE_MIN_INFLATE_RATIO);
        ZipSecureFile.setMaxEntrySize(SECURE_MAX_ENTRY_SIZE);
        ZipSecureFile.setMaxTextSize(SECURE_MAX_TEXT_SIZE);

        // 回读断言：防止 JVM 系统属性 / 反射 / 未来 POI 版本变更导致设置失效
        double actualRatio = ZipSecureFile.getMinInflateRatio();
        long actualEntrySize = ZipSecureFile.getMaxEntrySize();
        long actualTextSize = ZipSecureFile.getMaxTextSize();

        if (Double.compare(actualRatio, SECURE_MIN_INFLATE_RATIO) != 0
                || actualEntrySize != SECURE_MAX_ENTRY_SIZE
                || actualTextSize != SECURE_MAX_TEXT_SIZE) {
            throw new IllegalStateException(String.format(
                    "ZipSecureFile thresholds could not be pinned securely. " +
                            "Expected ratio=%.4f entrySize=%d textSize=%d, " +
                            "but got ratio=%.4f entrySize=%d textSize=%d. " +
                            "Check for conflicting -Dpoi.* system properties or POI version drift.",
                    SECURE_MIN_INFLATE_RATIO, SECURE_MAX_ENTRY_SIZE, SECURE_MAX_TEXT_SIZE,
                    actualRatio, actualEntrySize, actualTextSize));
        }

        log.info("ZipSecureFile defenses pinned: minInflateRatio={}, maxEntrySize={}, maxTextSize={}",
                actualRatio, actualEntrySize, actualTextSize);
    }
}
