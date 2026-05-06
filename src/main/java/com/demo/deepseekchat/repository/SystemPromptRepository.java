package com.demo.deepseekchat.repository;

import com.demo.deepseekchat.model.entity.SystemPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 系统提示词 Repository
 */
public interface SystemPromptRepository extends JpaRepository<SystemPrompt, Long> {

    Optional<SystemPrompt> findByModelId(String modelId);

    boolean existsByModelId(String modelId);

    void deleteByModelId(String modelId);
}
