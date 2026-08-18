package com.smart.rag.evaluation.dataset;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据集导出为 JSON（供人工审核用）
 * <p>
 * 导出格式：{ "dataset": {...}, "items": [...] }
 * </p>
 */
@Component
public class DatasetExporter {

    private final DatasetRepository datasetRepo;
    private final ObjectMapper objectMapper;

    public DatasetExporter(DatasetRepository datasetRepo, ObjectMapper objectMapper) {
        this.datasetRepo = datasetRepo;
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 导出数据集为 JSON 字符串
     */
    public String exportAsJson(long datasetId) {
        var dataset = datasetRepo.findDatasetById(datasetId)
                .orElseThrow(() -> new ServiceException(ServiceErrorCode.NOT_FOUND, "Dataset not found: " + datasetId));
        var items = datasetRepo.listItemsByDatasetId(datasetId);

        try {
            Map<String, Object> export = Map.of(
                    "dataset", dataset,
                    "items", items
            );
            return objectMapper.writeValueAsString(export);
        } catch (Exception e) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "Failed to export dataset", e);
        }
    }
}
