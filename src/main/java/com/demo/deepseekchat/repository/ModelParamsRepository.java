package com.demo.deepseekchat.repository;

import com.demo.deepseekchat.model.entity.ModelParams;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 模型参数 Repository
 */
public interface ModelParamsRepository extends JpaRepository<ModelParams, Long> {

    Optional<ModelParams> findByModelId(String modelId);

    boolean existsByModelId(String modelId);

    void deleteByModelId(String modelId);
}
