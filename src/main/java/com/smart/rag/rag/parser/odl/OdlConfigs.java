package com.smart.rag.rag.parser.odl;

import com.smart.rag.rag.config.DocumentProperties;
import org.opendataloader.pdf.api.Config;

/**
 * ODL Config 统一门面（design §6.2 H-C1 机制化 + §6.4 中-8a）。
 * <p>
 * 前台 parser 与后台 consumer 的唯一 {@link Config} 构造点：
 * <ul>
 *   <li>显式设置全部依赖项（不依赖库默认值——threads 默认 1、imageOutput 默认 external
 *       都不是契约）；</li>
 *   <li>每次调用前 fail-fast 校验 {@code hybrid=off}（H-C1）——{@code HybridDocumentProcessor}
 *       存在裸静态可变量（lastHybridTimings / lastElementMetadata 等），任何链路启用 hybrid
 *       即构成跨线程竞写，直接拒绝执行。禁止在门面外 {@code new Config()} 绕过校验。</li>
 * </ul>
 */
public final class OdlConfigs {

    /** manifest 行的版本戳（design §6.3 L2）：前台生成清单时的 ODL 版本，P3 对账检测跨版本消费 */
    public static final String PRODUCER_VERSION = "odl-2.5.5";

    private OdlConfigs() {
    }

    /**
     * 前台（索引关键路径）配置：零图片工作（imageOutput=off）+ 逐页并行 threads=N +
     * 不落盘（generateOutputs 不触发，Markdown 直写内存 Writer）。
     */
    public static Config foreground(DocumentProperties props) {
        Config config = base();
        config.setThreads(props.getOdlThreads());
        assertHybridOff(config);
        return config;
    }

    /**
     * 后台（图片提取）配置：仅用于 preprocessing 重建线程状态 + ImagesUtils 取图，
     * 输出开关全关。
     */
    public static Config background() {
        Config config = base();
        assertHybridOff(config);
        return config;
    }

    private static Config base() {
        Config config = new Config();
        config.setGenerateMarkdown(false);
        config.setGenerateJSON(false);
        config.setGenerateHtml(false);
        config.setGeneratePDF(false);
        config.setImageOutput(Config.IMAGE_OUTPUT_OFF);
        config.setIncludeHeaderFooter(false);
        config.setHybrid(Config.HYBRID_OFF);
        return config;
    }

    /**
     * H-C1 fail-fast：hybrid 模式与并发提取不兼容（§3.2 裸静态量）。
     * 提取/预处理调用前必须校验。
     */
    public static void assertHybridOff(Config config) {
        if (!Config.HYBRID_OFF.equals(config.getHybrid())) {
            throw new IllegalStateException(
                    "H-C1 violated: hybrid mode is incompatible with concurrent extraction (see design §3.2)");
        }
    }
}
