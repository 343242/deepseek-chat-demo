package com.smart.rag.rag.parser.odl;

import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.ObjectKey;
import org.verapdf.wcag.algorithms.entities.SemanticTOC;
import org.verapdf.wcag.algorithms.entities.SemanticTOCI;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.lists.PDFList;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片编号器（design §6.2）——前台的唯一编号事实源。
 * <p>
 * <b>遍历域是 {@code MarkdownGenerator} 输出域的完整镜像</b>（v1.4 高-1 修正）：
 * 门控镜像 {@code isSupportedContent}（{@code includeHeaderFooter=false} 时整枝剪掉
 * 页眉脚子树，含其嵌套图）+ 递归镜像 {@code write} 分发（表格按 row×col 逐格递归、
 * 列表经 listItem.getContents、TOC 经 SemanticTOCI.getContents——v1.6 中-1）。
 * 两遍历任何门控/递归分歧的第一现场是 H3 数量断言失败（fail-closed）。
 * <p>
 * <b>按遍历出现记录</b>（v1.5 高-2）：同一图片对象多处引用时每次出现各记一条、
 * 各分配 seq——与占位符生成器的独立计数器经同构遍历保证第 k 次出现两侧对齐。
 * <p>
 * 结构事实（EXP7）：本地模式（hybrid=off）不产生 SemanticPicture，PAGE_RENDER 条目
 * 仅来自无 XObject key 的 ImageChunk（内联图等）兜底——镜像 ODL {@code createImageFile}
 * 的回退逻辑。SemanticPicture 分支保留为 hybrid 未来的安全位。
 */
public final class ImageNumberer {

    private final List<ImageManifest.ImageEntry> entries = new ArrayList<>();
    private int seq = 0;

    /**
     * 按前台管线语义遍历 contents（每页一个 List&lt;IObject&gt;），
     * 记录图片清单。isImageSupported 恒 true（占位符生成器子类已置 true）。
     */
    public static ImageManifest number(List<List<IObject>> contents) {
        ImageNumberer numberer = new ImageNumberer();
        for (List<IObject> page : contents) {
            if (page == null) {
                continue;
            }
            for (IObject object : page) {
                if (object != null && isSupportedContent(object)) {
                    numberer.write(object);
                }
            }
        }
        return new ImageManifest(numberer.entries);
    }

    /** 门控——逐字节镜像 MarkdownGenerator.isSupportedContent（isImageSupported=true） */
    static boolean isSupportedContent(IObject object) {
        if (object instanceof org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter) {
            // Config.includeHeaderFooter = false（门面显式设置）→ 整枝剪掉
            return false;
        }
        return object instanceof org.verapdf.wcag.algorithms.entities.SemanticTextNode
                || object instanceof org.opendataloader.pdf.entities.SemanticFormula
                || object instanceof org.opendataloader.pdf.entities.SemanticPicture
                || object instanceof TableBorder
                || object instanceof PDFList
                || object instanceof SemanticTOC
                || object instanceof ImageChunk;
    }

    /** 分发——镜像 MarkdownGenerator.write 的 instanceof 链与递归规则 */
    private void write(IObject object) {
        // write 分发顺序：HeaderOrFooter(不可达，门控已剪) → Picture → ImageChunk → Formula →
        // Heading → Paragraph → TextNode → Table → List → TOC
        if (object instanceof org.opendataloader.pdf.entities.SemanticPicture picture) {
            recordEntry(ImageManifest.ImageEntry.TYPE_PAGE_RENDER, picture.getPageNumber(),
                    picture.getBoundingBox());
        } else if (object instanceof ImageChunk imageChunk) {
            ObjectKey key = xObjectKey(imageChunk);
            if (key != null) {
                String name = null;
                if (imageChunk.getStreamInfos() != null && !imageChunk.getStreamInfos().isEmpty()
                        && imageChunk.getStreamInfos().get(0).getXObjectName() != null) {
                    name = imageChunk.getStreamInfos().get(0).getXObjectName();
                }
                entries.add(new ImageManifest.ImageEntry(
                        ImageManifest.ImageEntry.TYPE_XOBJECT,
                        pageIndex(imageChunk),
                        seq++,
                        bbox(imageChunk),
                        key.getNumber(),
                        key.getGeneration(),
                        name));
            } else {
                // 无 XObject key（内联图等）→ 页区域渲染兜底（镜像 ODL createImageFile 回退）
                recordEntry(ImageManifest.ImageEntry.TYPE_PAGE_RENDER,
                        imageChunk.getPageNumber(), imageChunk.getBoundingBox());
            }
        } else if (object instanceof TableBorder table) {
            // writeTable：逐 row × col 递归 cell.getContents()
            for (int r = 0; r < table.getNumberOfRows(); r++) {
                var row = table.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = 0; c < table.getNumberOfColumns(); c++) {
                    var cell = row.getCell(c);
                    if (cell != null && cell.getContents() != null) {
                        walkContents(cell.getContents());
                    }
                }
            }
        } else if (object instanceof PDFList list) {
            // writeList：递归 listItem.getContents()
            if (list.getListItems() != null) {
                for (var item : list.getListItems()) {
                    if (item.getContents() != null) {
                        walkContents(item.getContents());
                    }
                }
            }
        } else if (object instanceof SemanticTOC toc) {
            // writeTOC：嵌套 TOC 继续递归；TOCI 递归其 getContents()
            if (toc.getTOCItems() != null) {
                for (IObject item : toc.getTOCItems()) {
                    if (item instanceof SemanticTOC nested) {
                        write(nested);
                    } else if (item instanceof SemanticTOCI toci && toci.getContents() != null) {
                        walkContents(toci.getContents());
                    }
                }
            }
        }
        // SemanticFormula / SemanticTextNode（含 Heading/Paragraph）：叶子，无图片
    }

    private void walkContents(List<IObject> contents) {
        for (IObject object : contents) {
            if (object != null && isSupportedContent(object)) {
                write(object);
            }
        }
    }

    private void recordEntry(String type, Integer pageNumber,
                             org.verapdf.wcag.algorithms.entities.geometry.BoundingBox box) {
        int page = pageNumber != null ? pageNumber : 0;
        double[] b = box != null
                ? new double[]{box.getLeftX(), box.getBottomY(), box.getRightX(), box.getTopY()}
                : new double[]{0, 0, 0, 0};
        entries.add(new ImageManifest.ImageEntry(type, page, seq++, b, null, null, null));
    }

    private static ObjectKey xObjectKey(ImageChunk imageChunk) {
        var infos = imageChunk.getStreamInfos();
        if (infos == null || infos.isEmpty()) {
            return null;
        }
        return infos.get(0).getXImageObjectKey();
    }

    private static int pageIndex(ImageChunk imageChunk) {
        Integer page = imageChunk.getPageNumber();
        return page != null ? page : 0;
    }

    private static double[] bbox(ImageChunk imageChunk) {
        var box = imageChunk.getBoundingBox();
        if (box == null) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{box.getLeftX(), box.getBottomY(), box.getRightX(), box.getTopY()};
    }
}
