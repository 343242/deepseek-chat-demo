package com.smart.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * PDF 图片清单行（design §6.3）。
 * <p>
 * 状态机：PENDING →（上传成功）UPLOADED /（结构性不可恢复）SKIPPED /（重试预算耗尽，
 * 由 P3 超龄扫描终态化）FAILED。行状态迁移一律条件化
 * {@code UPDATE ... WHERE id=? AND status='PENDING'}（高-2 代际失效协议）。
 */
@TableName("document_image")
public class DocumentImage {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 0-based */
    private Integer pageNumber;

    /** 文档内连续序号 */
    private Integer seq;

    /** XOBJECT | PAGE_RENDER */
    private String imgType;

    /** [l,b,r,t] JSON 文本（JSONB 列） */
    private String bbox;

    private Integer objectNum;

    private Integer objectGen;

    private String xObjectName;

    private String storageKey;

    /** 前台生成 manifest 时的 ODL 版本戳（如 odl-2.5.5） */
    private String producerVersion;

    private String status;

    private String failReason;

    private Long fileSize;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getImgType() { return imgType; }
    public void setImgType(String imgType) { this.imgType = imgType; }
    public String getBbox() { return bbox; }
    public void setBbox(String bbox) { this.bbox = bbox; }
    public Integer getObjectNum() { return objectNum; }
    public void setObjectNum(Integer objectNum) { this.objectNum = objectNum; }
    public Integer getObjectGen() { return objectGen; }
    public void setObjectGen(Integer objectGen) { this.objectGen = objectGen; }
    public String getXObjectName() { return xObjectName; }
    public void setXObjectName(String xObjectName) { this.xObjectName = xObjectName; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getProducerVersion() { return producerVersion; }
    public void setProducerVersion(String producerVersion) { this.producerVersion = producerVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
