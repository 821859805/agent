package com.chatbi.agent.chatbi_server_java.repository;


import com.chatbi.agent.chatbi_server_java.model.LlmModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmModelConfigRepository extends JpaRepository<LlmModelConfig, Long> {
    
    /**
     * 查询所有启用的模型，按排序顺序
     */
    List<LlmModelConfig> findByIsEnabledTrueOrderBySortOrderAsc();
    
    /**
     * 根据model_id查询
     */
    Optional<LlmModelConfig> findByModelId(String modelId);
    
    /**
     * 查询默认模型
     */
    Optional<LlmModelConfig> findByIsDefaultTrueAndIsEnabledTrue();
    
    /**
     * 根据提供商查询
     */
    List<LlmModelConfig> findByProviderAndIsEnabledTrue(String provider);
    
    /**
     * 检查model_id是否存在
     */
    boolean existsByModelId(String modelId);
}

