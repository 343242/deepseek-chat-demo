package com.smart.rag.rag.parser.odl;

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.verapdf.containers.StaticCoreContainers;
import org.verapdf.gf.model.impl.containers.StaticStorages;

import org.verapdf.tools.StaticResources;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.xmp.containers.StaticXmpCoreContainers;

import java.util.List;

/**
 * ODL 清理镜像（design §3.3）。
 * <p>
 * {@code DocumentProcessor.closePdfResources()} 是 private 且只在 {@code processFileWithResult}
 * 的 finally 中执行——独立调用 {@code extractContents}/{@code preprocessing} 的线程必须手动
 * 执行本镜像（EXP2' 实锤：不清理则每文档泄漏打开的文件句柄与堆内 COS 对象）。
 * <p>
 * 步骤序列与 2.5.5 {@code closePdfResources} 的 {@code clearCleanupStep("…")} 序列一一对应
 * （{@code MIRROR_STEPS}）；契约测试读取字节码断言两者完全一致，ODL 升级若清理序列变化
 * CI 即刻失败。
 */
public final class OdlResourceCleaner {

    private OdlResourceCleaner() {
    }

    /**
     * 清理步骤名序列——与 {@code DocumentProcessor.closePdfResources} 字节码中
     * {@code clearCleanupStep("…")} 的字符串参数序列完全一致（§8.2 契约测试锁定）。
     */
    public static final List<String> MIRROR_STEPS = List.of(
            "PDDocument",
            "ImagesUtils",
            "StaticResources",
            "StaticContainers",
            "GFStaticContainers",
            "StaticLayoutContainers",
            "StaticStorages",
            "StaticCoreContainers",
            "StaticXmpCoreContainers"
    );

    /**
     * 执行九步清理镜像。必须在调用过 {@code preprocessing}/{@code extractContents}
     * 的同一线程上执行（ThreadLocal 容器模型）。任一步失败仅告警、继续后续步骤
     * （对齐源码 clearCleanupStep 的容错语义）。
     */
    public static void cleanupMirror() {
        step("PDDocument", () -> {
            var doc = StaticResources.getDocument();
            if (doc != null) {
                doc.close();
            }
        });
        step("ImagesUtils", StaticContainers::closeImagesUtils);
        step("StaticResources", StaticResources::clear);
        step("StaticContainers", () -> StaticContainers.updateContainers(null));
        step("GFStaticContainers", org.verapdf.gf.model.impl.containers.StaticContainers::clearAllContainers);
        step("StaticLayoutContainers", StaticLayoutContainers::clearContainers);
        step("StaticStorages", StaticStorages::clearAllContainers);
        step("StaticCoreContainers", StaticCoreContainers::clearAllContainers);
        step("StaticXmpCoreContainers", StaticXmpCoreContainers::clearAllContainers);
    }

    private static void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            // 静态容器清理失败不致命（泄漏风险已记录），继续清理其余容器
        }
    }
}
