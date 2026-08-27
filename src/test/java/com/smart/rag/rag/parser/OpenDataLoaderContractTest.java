package com.smart.rag.rag.parser;

import com.smart.rag.rag.parser.odl.ImageManifest;
import com.smart.rag.rag.parser.odl.ImageNumberer;
import com.smart.rag.rag.parser.odl.OdlConfigs;
import com.smart.rag.rag.parser.odl.OdlResourceCleaner;
import com.smart.rag.rag.parser.odl.PlaceholderMarkdownGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.opendataloader.pdf.processors.ExtractionResult;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.verapdf.tools.StaticResources;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ODL 契约测试（design §8）——固化 §3 事实卡，P1/P2 放行门槛。
 * 不起 Spring 容器，直接依赖 jar；ODL 升级若破坏任一契约，CI 即刻失败。
 * <p>
 * 未在本类固化的造图用例（表格嵌套多图/页眉脚嵌图/TOC 嵌图——§8.5）：前两者已在设计
 * 阶段经 EXP6 原型动态验证（§3.5），TOC-CASE 依 §10 为 P2 开工前原型补跑项；
 * 其合成依赖 ODL 结构识别启发式，转 CI 用例时以 EXP6 同型原型迁移。
 */
class OpenDataLoaderContractTest {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\]\\(/api/documents/\\d+/images/p\\d+-\\d+\\.[a-z]+\\)");

    private final List<Path> temps = new ArrayList<>();

    @AfterEach
    void cleanup() {
        OdlResourceCleaner.cleanupMirror();
        for (Path p : temps) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== §8.1 扩展点存在性 ====================

    @Test
    void extensionPointsExistWithExpectedVisibility() throws Exception {
        Class<?> dp = Class.forName("org.opendataloader.pdf.processors.DocumentProcessor");
        assertModifier(dp.getMethod("extractContents", String.class, Config.class), Modifier.PUBLIC, "extractContents");
        assertModifier(dp.getMethod("preprocessing", String.class, Config.class), Modifier.PUBLIC, "preprocessing");

        Class<?> mg = Class.forName("org.opendataloader.pdf.markdown.MarkdownGenerator");
        assertModifier(mg.getConstructor(java.io.Writer.class, Config.class), Modifier.PUBLIC, "MarkdownGenerator(Writer,Config)");
        assertModifier(mg.getMethod("writeToMarkdown", List.class), Modifier.PUBLIC, "writeToMarkdown");
        assertTrue(Modifier.isProtected(mg.getDeclaredField("isImageSupported").getModifiers()), "isImageSupported");
        assertModifier(mg.getDeclaredMethod("writeImage",
                        Class.forName("org.verapdf.wcag.algorithms.entities.content.ImageChunk")),
                Modifier.PROTECTED, "writeImage");
        assertModifier(mg.getDeclaredMethod("writePicture",
                        Class.forName("org.opendataloader.pdf.entities.SemanticPicture")),
                Modifier.PROTECTED, "writePicture");

        Class<?> iu = Class.forName("org.verapdf.wcag.algorithms.semanticalgorithms.utils.ImagesUtils");
        assertModifier(iu.getMethod("getXObjectImage", int.class,
                Class.forName("org.verapdf.wcag.algorithms.entities.ObjectKey")), Modifier.PUBLIC, "getXObjectImage");
        assertModifier(iu.getMethod("getPageSubImage",
                Class.forName("org.verapdf.wcag.algorithms.entities.geometry.BoundingBox"), Double.class),
                Modifier.PUBLIC, "getPageSubImage");
        assertModifier(iu.getMethod("clearRenderedPages"), Modifier.PUBLIC, "clearRenderedPages");
    }

    private static void assertModifier(java.lang.reflect.Member member, int expected, String name) {
        int mods = member.getModifiers();
        assertTrue((mods & expected) != 0,
                name + " visibility changed: mods=" + Modifier.toString(mods));
    }

    // ==================== §8.2 清理镜像对齐（H1） ====================

    @Test
    void cleanupMirrorMatchesClosePdfResourcesBytecode() throws Exception {
        String resource = "/org/opendataloader/pdf/processors/DocumentProcessor.class";
        byte[] bytes = getClass().getResourceAsStream(resource).readAllBytes();
        List<String> steps = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"closePdfResources".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private String pendingLdc;

                    @Override
                    public void visitLdcInsn(Object value) {
                        pendingLdc = value instanceof String s ? s : null;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String desc, boolean isInterface) {
                        if ("clearCleanupStep".equals(mname) && pendingLdc != null) {
                            steps.add(pendingLdc);
                            pendingLdc = null;
                        }
                    }
                };
            }
        }, 0);
        assertEquals(OdlResourceCleaner.MIRROR_STEPS, steps,
                "closePdfResources 清理序列变化——镜像已漂移，须同步修订 OdlResourceCleaner（design §3.3）");
        assertEquals(9, steps.size());
    }

    // ==================== §8.3 行为契约 + §8.5 双射 ====================

    @Test
    void extractContentsWithImagesOffYieldsImageChunkWithObjectKeyAndBijection() throws Exception {
        Path pdf = imagePdf("contract-basic", 3, 120, 80);
        Config config = OdlConfigs.foreground(testProps());
        ExtractionResult result = DocumentProcessor.extractContents(pdf.toString(), config);
        try {
            ImageManifest manifest = ImageNumberer.number(result.getContents());
            assertTrue(manifest.size() >= 3, "images=off 时 contents 应含 ImageChunk（§3.4）: " + manifest.size());
            for (ImageManifest.ImageEntry e : manifest.entries()) {
                if ("XOBJECT".equals(e.type())) {
                    assertNotNull(e.objectNum(), "XOBJECT 条目应携带 ObjectKey");
                }
            }

            String markdown = render(result, manifest, 42L);
            long count = PLACEHOLDER.matcher(markdown).results().count();
            assertEquals(manifest.size(), count, "占位符↔manifest 双射（§8.5 M5）");
            assertFalse(markdown.contains("/images/missing"));
            assertTrue(markdown.contains("/api/documents/42/images/"));
        } finally {
            OdlResourceCleaner.cleanupMirror();
        }
    }

    @Test
    void cleanupMirrorLeavesNoResidue() throws Exception {
        Path pdf = imagePdf("contract-cleanup", 1, 50, 50);
        Config config = OdlConfigs.foreground(testProps());
        DocumentProcessor.extractContents(pdf.toString(), config);
        OdlResourceCleaner.cleanupMirror();
        assertNull(StaticResources.getDocument(), "清理后 PDDocument 应为 null（无句柄泄漏）");
        // 不能用 getImagesUtils() 断言——它本身会懒创建实例（§3.4 M7）；
        // 经反射读 ThreadLocal 字段做非创建式探测
        var field = StaticContainers.class.getDeclaredField("imagesUtils");
        field.setAccessible(true);
        assertNull(((ThreadLocal<?>) field.get(null)).get(), "清理后 ImagesUtils 应为 null");
    }

    // ==================== §8.4 并发隔离（EXP4/EXP5，P1 放行门槛） ====================

    @Test
    void concurrentExtractionDifferentPdfsMatchesReferenceByteForByte() throws Exception {
        Path pdfA = imagePdf("contract-exp4-a", 5, 200, 100);
        Path pdfB = imagePdf("contract-exp4-b", 6, 100, 200);
        String refA = fullPipeline(pdfA);
        String refB = fullPipeline(pdfB);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                Future<String> fa = pool.submit(() -> fullPipeline(pdfA));
                Future<String> fb = pool.submit(() -> fullPipeline(pdfB));
                assertEquals(refA, fa.get(), "EXP4 第 " + round + " 轮 A 输出漂移");
                assertEquals(refB, fb.get(), "EXP4 第 " + round + " 轮 B 输出漂移");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentExtractionSamePdfMatchesReferenceByteForByte() throws Exception {
        Path pdf = imagePdf("contract-exp5", 5, 160, 120);
        String ref = fullPipeline(pdf);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                Future<String> f1 = pool.submit(() -> fullPipeline(pdf));
                Future<String> f2 = pool.submit(() -> fullPipeline(pdf));
                assertEquals(ref, f1.get(), "EXP5 第 " + round + " 轮线程1 输出漂移");
                assertEquals(ref, f2.get(), "EXP5 第 " + round + " 轮线程2 输出漂移");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== helpers ====================

    /** 完整前台管线（线程内独立执行：extractContents → number → placeholder → 清理） */
    private static String fullPipeline(Path pdf) throws Exception {
        try {
            Config config = OdlConfigs.foreground(testProps());
            ExtractionResult result = DocumentProcessor.extractContents(pdf.toString(), config);
            ImageManifest manifest = ImageNumberer.number(result.getContents());
            String markdown = render(result, manifest, 1L);
            long count = PLACEHOLDER.matcher(markdown).results().count();
            if (count != manifest.size()) {
                throw new IllegalStateException("bijection broken: " + count + " vs " + manifest.size());
            }
            return markdown;
        } finally {
            OdlResourceCleaner.cleanupMirror();
        }
    }

    private static String render(ExtractionResult result, ImageManifest manifest, long docId) throws Exception {
        StringWriter sw = new StringWriter();
        try (PlaceholderMarkdownGenerator gen = new PlaceholderMarkdownGenerator(
                sw, OdlConfigs.foreground(testProps()), manifest,
                new ParseContext(docId, "b", "k", "f.pdf"))) {
            gen.writeToMarkdown(result.getContents());
        }
        return sw.toString();
    }

    private static com.smart.rag.rag.config.DocumentProperties testProps() {
        com.smart.rag.rag.config.DocumentProperties props = new com.smart.rag.rag.config.DocumentProperties();
        props.setOdlThreads(2);   // 触发逐页并行 + 公共池排序路径（§8.4 覆盖要求）
        return props;
    }

    /** PDFBox 合成带嵌入 PNG 的多页 PDF（复用设计验证实验的造图手法） */
    private Path imagePdf(String name, int pages, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(30, 90, 200));
        g.fillRect(0, 0, w, h);
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        byte[] png;
        try (var bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            png = bos.toByteArray();
        }
        Path pdf = Files.createTempFile(name + "-", ".pdf");
        temps.add(pdf);
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument();
             var out = Files.newOutputStream(pdf)) {
            for (int i = 0; i < pages; i++) {
                var page = new org.apache.pdfbox.pdmodel.PDPage(
                        new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
                doc.addPage(page);
                var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page);
                content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
                        .createFromImage(doc, img), 100, 500, w, h);
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Page " + (i + 1) + " with embedded image");
                content.endText();
                content.close();
            }
            doc.save(out);
        }
        return pdf;
    }
}
