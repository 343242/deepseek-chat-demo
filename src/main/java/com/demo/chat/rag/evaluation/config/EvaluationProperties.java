package com.demo.chat.rag.evaluation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 评估系统配置
 * <p>
 * 对应 application-evaluation.yml 中 app.evaluation.* 配置项。
 * 仅在 evaluation profile 激活时生效。
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "app.evaluation")
public class EvaluationProperties {

    /** 是否启用评估模块（需同时激活 evaluation profile） */
    private boolean enabled = false;

    /** Judge 模型（用于生成侧指标评估） */
    private String judgeModel = "zai/glm-5.1";

    /** 生成模型（用于 Pipeline 答案生成） */
    private String generationModel = "deepseek/deepseek-v4-pro";

    /** 评估使用的测试用户 ID（需确保该用户有足够数据） */
    private Long testUserId = 1L;

    /** 数据集相关配置 */
    private Dataset dataset = new Dataset();

    /** 运行器相关配置 */
    private Runner runner = new Runner();

    // ======================== Getters & Setters ========================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJudgeModel() {
        return judgeModel;
    }

    public void setJudgeModel(String judgeModel) {
        this.judgeModel = judgeModel;
    }

    public String getGenerationModel() {
        return generationModel;
    }

    public void setGenerationModel(String generationModel) {
        this.generationModel = generationModel;
    }

    public Long getTestUserId() {
        return testUserId;
    }

    public void setTestUserId(Long testUserId) {
        this.testUserId = testUserId;
    }

    public Dataset getDataset() {
        return dataset;
    }

    public void setDataset(Dataset dataset) {
        this.dataset = dataset;
    }

    public Runner getRunner() {
        return runner;
    }

    public void setRunner(Runner runner) {
        this.runner = runner;
    }

    // ======================== 嵌套配置类 ========================

    public static class Dataset {
        /** LLM 自动生成时的采样 chunk 数 */
        private int sampleSize = 50;

        /** 每个 chunk 生成的问题数 */
        private int questionsPerChunk = 2;

        public int getSampleSize() {
            return sampleSize;
        }

        public void setSampleSize(int sampleSize) {
            this.sampleSize = sampleSize;
        }

        public int getQuestionsPerChunk() {
            return questionsPerChunk;
        }

        public void setQuestionsPerChunk(int questionsPerChunk) {
            this.questionsPerChunk = questionsPerChunk;
        }
    }

    public static class Runner {
        /** 默认评估的 topK */
        private int defaultK = 10;

        /** 并发评估数（避免打爆 API） */
        private int concurrency = 1;

        /** 单条评估超时（秒） */
        private int timeoutSeconds = 300;

        public int getDefaultK() {
            return defaultK;
        }

        public void setDefaultK(int defaultK) {
            this.defaultK = defaultK;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
