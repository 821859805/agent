package com.chatbi.agent.chatbi_server_java.service;


import com.chatbi.agent.chatbi_server_java.model.LlmModelConfig;
import com.chatbi.agent.chatbi_server_java.repository.LlmModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class LlmModelConfigService {
    
    private static final Logger logger = LoggerFactory.getLogger(LlmModelConfigService.class);
    
    @Autowired
    private LlmModelConfigRepository repository;
    
    @Autowired(required = false)
    @Lazy
    private LlmModelFactoryService llmModelFactoryService;
    
    /**
     * 获取所有模型配置
     */
    public List<LlmModelConfig> getAllModels() {
        return repository.findAll();
    }
    
    /**
     * 获取所有启用的模型
     */
    public List<LlmModelConfig> getEnabledModels() {
        return repository.findByIsEnabledTrueOrderBySortOrderAsc();
    }
    
    /**
     * 根据ID获取模型
     */
    public Optional<LlmModelConfig> getModelById(Long id) {
        return repository.findById(id);
    }
    
    /**
     * 根据model_id获取模型
     */
    public Optional<LlmModelConfig> getModelByModelId(String modelId) {
        return repository.findByModelId(modelId);
    }
    
    /**
     * 获取默认模型
     */
    public Optional<LlmModelConfig> getDefaultModel() {
        return repository.findByIsDefaultTrueAndIsEnabledTrue();
    }
    
    /**
     * 创建新模型配置
     */
    @Transactional
    public LlmModelConfig createModel(LlmModelConfig model) {
        // 如果设置为默认模型，取消其他模型的默认状态
        if (Boolean.TRUE.equals(model.getIsDefault())) {
            clearDefaultModels();
        }
        
        return repository.save(model);
    }
    
    /**
     * 更新模型配置
     */
    @Transactional
    public LlmModelConfig updateModel(Long id, LlmModelConfig updatedModel) {
        LlmModelConfig existing = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Model not found with id: " + id));
        
        // 保存旧的 model_id，用于清除缓存
        String oldModelId = existing.getModelId();
        
        // 更新字段
        existing.setName(updatedModel.getName());
        existing.setProvider(updatedModel.getProvider());
        existing.setBaseUrl(updatedModel.getBaseUrl());
        existing.setApiKey(updatedModel.getApiKey());
        existing.setModelType(updatedModel.getModelType());
        existing.setMaxTokens(updatedModel.getMaxTokens());
        existing.setTemperature(updatedModel.getTemperature());
        existing.setTimeoutSeconds(updatedModel.getTimeoutSeconds());
        existing.setDescription(updatedModel.getDescription());
        existing.setIsEnabled(updatedModel.getIsEnabled());
        existing.setSortOrder(updatedModel.getSortOrder());
        
        // 如果设置为默认模型，取消其他模型的默认状态
        if (Boolean.TRUE.equals(updatedModel.getIsDefault()) && !Boolean.TRUE.equals(existing.getIsDefault())) {
            clearDefaultModels();
        }
        existing.setIsDefault(updatedModel.getIsDefault());
        
        LlmModelConfig saved = repository.save(existing);
        
        // 清除旧 model_id 的缓存
        if (llmModelFactoryService != null && oldModelId != null) {
            llmModelFactoryService.clearCache(oldModelId);
            logger.info("Cleared cache for old model_id: {}", oldModelId);
        }
        
        // 如果 model_id 改变了，也清除新 model_id 的缓存（如果存在）
        if (saved.getModelId() != null && !saved.getModelId().equals(oldModelId)) {
            if (llmModelFactoryService != null) {
                llmModelFactoryService.clearCache(saved.getModelId());
                logger.info("Cleared cache for new model_id: {}", saved.getModelId());
            }
        }
        
        return saved;
    }
    
    /**
     * 删除模型配置
     */
    @Transactional
    public void deleteModel(Long id) {
        LlmModelConfig model = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Model not found with id: " + id));
        
        // 保存 model_id 用于清除缓存
        String modelId = model.getModelId();
        
        // 如果删除的是默认模型，警告
        if (Boolean.TRUE.equals(model.getIsDefault())) {
            logger.warn("Deleting default model: {}", modelId);
        }
        
        repository.deleteById(id);
        
        // 清除缓存
        if (llmModelFactoryService != null && modelId != null) {
            llmModelFactoryService.clearCache(modelId);
            logger.info("Cleared cache for deleted model_id: {}", modelId);
        }
    }
    
    /**
     * 设置默认模型
     */
    @Transactional
    public LlmModelConfig setDefaultModel(Long id) {
        LlmModelConfig model = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Model not found with id: " + id));
        
        // 清除其他模型的默认状态
        clearDefaultModels();
        
        // 设置为默认并启用
        model.setIsDefault(true);
        model.setIsEnabled(true);
        
        LlmModelConfig saved = repository.save(model);
        
        // 清除缓存，确保使用新的配置
        if (llmModelFactoryService != null && saved.getModelId() != null) {
            llmModelFactoryService.clearCache(saved.getModelId());
            logger.info("Cleared cache for default model_id: {}", saved.getModelId());
        }
        
        return saved;
    }
    
    /**
     * 切换模型启用状态
     */
    @Transactional
    public LlmModelConfig toggleEnabled(Long id) {
        LlmModelConfig model = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Model not found with id: " + id));
        
        boolean newStatus = !Boolean.TRUE.equals(model.getIsEnabled());
        model.setIsEnabled(newStatus);
        
        // 如果禁用默认模型，取消默认状态
        if (!newStatus && Boolean.TRUE.equals(model.getIsDefault())) {
            model.setIsDefault(false);
            logger.warn("Disabled default model, clearing default status: {}", model.getModelId());
        }
        
        LlmModelConfig saved = repository.save(model);
        
        // 清除缓存，确保状态变更生效
        if (llmModelFactoryService != null && saved.getModelId() != null) {
            llmModelFactoryService.clearCache(saved.getModelId());
            logger.info("Cleared cache for toggled model_id: {}", saved.getModelId());
        }
        
        return saved;
    }
    
    /**
     * 清除所有模型的默认状态
     */
    private void clearDefaultModels() {
        List<LlmModelConfig> allModels = repository.findAll();
        for (LlmModelConfig model : allModels) {
            if (Boolean.TRUE.equals(model.getIsDefault())) {
                model.setIsDefault(false);
                repository.save(model);
            }
        }
    }
}

