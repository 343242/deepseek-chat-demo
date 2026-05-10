package com.demo.chat.rag.dto;

public class DocumentUploadResponse {

    private Long id;
    private String fileName;
    private String status;

    public DocumentUploadResponse() {}

    public DocumentUploadResponse(Long id, String fileName, String status) {
        this.id = id;
        this.fileName = fileName;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
