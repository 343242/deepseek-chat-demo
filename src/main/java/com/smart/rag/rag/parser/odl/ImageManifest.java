package com.smart.rag.rag.parser.odl;

import java.util.List;

/**
 * 图片清单（design §6.2/§6.3）——前台编号一次、随 {@code document_image} 表持久化，
 * 后台按清单逐条执行，占位符与上传图片的对应关系由清单保证而非跨运行提取确定性。
 */
public record ImageManifest(List<ImageEntry> entries) {

    public ImageManifest {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    /**
     * @param type       XOBJECT（嵌入位图直解）| PAGE_RENDER（页区域渲染）
     * @param pageNumber 0-based 页号
     * @param seq        文档内连续序号（按遍历出现递增，v1.5 高-2）
     * @param bbox       [leftX, bottomY, rightX, topY]（double×4）
     * @param objectNum  XOBJECT: ObjectKey.number（PAGE_RENDER 为 null）
     * @param objectGen  XOBJECT: ObjectKey.generation（PAGE_RENDER 为 null）
     * @param xObjectName XOBJECT: 资源名（诊断用）
     */
    public record ImageEntry(String type, int pageNumber, int seq, double[] bbox,
                             Integer objectNum, Integer objectGen, String xObjectName) {

        public static final String TYPE_XOBJECT = "XOBJECT";
        public static final String TYPE_PAGE_RENDER = "PAGE_RENDER";

        /** ext 仅由 img_type 决定（v1.3 高-2：构造保证确定性，无条件分支） */
        public String ext() {
            return TYPE_PAGE_RENDER.equals(type) ? "jpeg" : "png";
        }

        public String mime() {
            return TYPE_PAGE_RENDER.equals(type) ? "image/jpeg" : "image/png";
        }

        /** 占位符 URL 的文件名部分：p{page+1}-{seq}.{ext} */
        public String urlName() {
            return "p" + (pageNumber + 1) + "-" + seq + "." + ext();
        }

        /** 存储对象 key（不含 bucket）：images/{documentId}/p{page+1}-{seq}.{ext} */
        public String storageKey(long documentId) {
            return "images/" + documentId + "/" + urlName();
        }
    }
}
