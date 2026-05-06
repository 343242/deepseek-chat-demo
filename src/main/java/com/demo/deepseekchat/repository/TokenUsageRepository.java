package com.demo.deepseekchat.repository;

import com.demo.deepseekchat.model.dto.UsageStats;
import com.demo.deepseekchat.model.entity.TokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 用量 Repository
 */
public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    List<TokenUsage> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    List<TokenUsage> findByModelIdOrderByCreatedAtDesc(String modelId);

    @Query("SELECT new com.demo.deepseekchat.model.dto.UsageStats(" +
           "u.modelId, COUNT(u), SUM(u.promptTokens), SUM(u.completionTokens), " +
           "SUM(u.totalTokens), AVG(u.durationMs)) " +
           "FROM TokenUsage u " +
           "WHERE (:modelId IS NULL OR u.modelId = :modelId) " +
           "AND (:startTime IS NULL OR u.createdAt >= :startTime) " +
           "AND (:endTime IS NULL OR u.createdAt <= :endTime) " +
           "GROUP BY u.modelId")
    List<UsageStats> aggregateByModel(
            @Param("modelId") String modelId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT new com.demo.deepseekchat.model.dto.UsageStats(" +
           "u.conversationId, COUNT(u), SUM(u.promptTokens), SUM(u.completionTokens), " +
           "SUM(u.totalTokens), AVG(u.durationMs)) " +
           "FROM TokenUsage u " +
           "WHERE (:conversationId IS NULL OR u.conversationId = :conversationId) " +
           "AND (:startTime IS NULL OR u.createdAt >= :startTime) " +
           "AND (:endTime IS NULL OR u.createdAt <= :endTime) " +
           "GROUP BY u.conversationId")
    List<UsageStats> aggregateByConversation(
            @Param("conversationId") String conversationId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}
