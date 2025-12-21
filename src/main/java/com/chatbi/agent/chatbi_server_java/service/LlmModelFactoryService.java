package com.chatbi.agent.chatbi_server_java.service;


import com.chatbi.agent.chatbi_server_java.model.LlmModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM模型工厂服务
 * 根据model_id从数据库读取配置并创建ChatModel实例
 */
@Service
public class LlmModelFactoryService {
    
    private static final Logger logger = LoggerFactory.getLogger(LlmModelFactoryService.class);
    
    @Autowired(required = false)
    private LlmModelConfigService llmModelConfigService;
    
    // 缓存已创建的LLM实例，避免重复创建
    private final Map<String, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();
    
    /**
     * 根据model_id获取或创建ChatModel实例
     * @param modelId 模型ID
     * @return ChatModel实例，如果配置不存在或服务不可用则返回null
     */
    public ChatLanguageModel getOrCreateModel(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) {
            logger.warn("Model ID is null or empty, using default model");
            return getDefaultModel();
        }
        
        // 检查缓存
        if (modelCache.containsKey(modelId)) {
            return modelCache.get(modelId);
        }
        
        // 从数据库读取配置
        if (llmModelConfigService == null) {
            logger.error("LlmModelConfigService is not available");
            return null;
        }
        
        LlmModelConfig config = llmModelConfigService.getModelByModelId(modelId)
            .orElse(null);
        
        if (config == null) {
            logger.warn("Model config not found for model_id: {}, trying default model", modelId);
            return getDefaultModel();
        }
        
        if (!Boolean.TRUE.equals(config.getIsEnabled())) {
            logger.warn("Model {} is disabled, trying default model", modelId);
            return getDefaultModel();
        }
        
        // 创建模型实例
        ChatLanguageModel model = createModelFromConfig(config);
        if (model != null) {
            modelCache.put(modelId, model);
            logger.info("Created and cached ChatLanguageModel for model_id: {}", modelId);
        }
        
        return model;
    }
    
    /**
     * 获取默认模型
     */
    public ChatLanguageModel getDefaultModel() {
        if (llmModelConfigService == null) {
            logger.error("LlmModelConfigService is not available");
            return null;
        }
        
        LlmModelConfig defaultConfig = llmModelConfigService.getDefaultModel()
            .orElse(null);
        
        if (defaultConfig == null) {
            logger.error("No default model configured");
            return null;
        }
        
        String modelId = defaultConfig.getModelId();
        
        // 检查缓存
        if (modelCache.containsKey(modelId)) {
            return modelCache.get(modelId);
        }
        
        // 创建默认模型实例
        ChatLanguageModel model = createModelFromConfig(defaultConfig);
        if (model != null) {
            modelCache.put(modelId, model);
            logger.info("Created and cached default ChatLanguageModel for model_id: {}", modelId);
        }
        
        return model;
    }
    
    /**
     * 根据配置创建ChatModel实例
     */
    private ChatLanguageModel createModelFromConfig(LlmModelConfig config) {
        try {
            String provider = config.getProvider() != null ? config.getProvider().toLowerCase(java.util.Locale.ROOT) : "ollama";
            
            switch (provider) {
                case "ollama":
                    // Ollama 支持 OpenAI 兼容的 API，使用 OpenAiChatModel 通过 baseUrl 连接
                    return createOpenAiCompatibleModel(config);
                case "openai":
                case "openai-compatible":
                    return createOpenAiCompatibleModel(config);
                default:
                    logger.warn("Unknown provider: {}, using OpenAI compatible API", provider);
                    // 尝试 OpenAI 兼容 API（可能支持自定义 baseUrl）
                    return createOpenAiCompatibleModel(config);
            }
        } catch (Exception e) {
            logger.error("Failed to create ChatLanguageModel from config: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 创建OpenAI兼容模型实例
     * 支持 OpenAI、Ollama（通过 OpenAI 兼容 API）、vLLM、LocalAI 等兼容 OpenAI API 的服务
     */
    private ChatLanguageModel createOpenAiCompatibleModel(LlmModelConfig config) {
        // API Key（可选，某些兼容服务可能不需要）
        String apiKey = config.getApiKey();
        
        // Base URL（必需）
        String baseUrl = config.getBaseUrl();
        boolean isOpenAiOfficialUrl = false;
        
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            // 如果没有设置 baseUrl，使用 OpenAI 默认地址
            baseUrl = "https://api.openai.com/v1";
            isOpenAiOfficialUrl = true;
            logger.warn("Base URL not set for OpenAI compatible model, using default: {}", baseUrl);
        } else {
            // 如果是 ollama，需要确保 baseUrl 包含 /v1 路径
            String provider = config.getProvider() != null ? config.getProvider().toLowerCase(java.util.Locale.ROOT) : "";
            if ("ollama".equals(provider) && !baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
                // Ollama 的 OpenAI 兼容 API 通常在 /v1 路径下
                baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1" : baseUrl + "/v1";
                logger.info("Adjusted Ollama baseUrl to OpenAI compatible endpoint: {}", baseUrl);
            }
            // 检查是否是 OpenAI 官方地址
            isOpenAiOfficialUrl = baseUrl.equals("https://api.openai.com/v1");
        }
        
        // Model name（使用 model_id 或 modelType）
        String modelName = config.getModelId();
        
        if (modelName == null || modelName.trim().isEmpty()) {
            logger.error("Model name is required for OpenAI compatible model");
            return null;
        }
        
        // 超时时间
        int timeoutSeconds = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 60;
        
        // 温度
        BigDecimal temperature = config.getTemperature() != null 
            ? config.getTemperature() 
            : new BigDecimal("0.7");
        
        // Max tokens - 验证范围 [1, 32768]
        Integer maxTokens = config.getMaxTokens() != null ? config.getMaxTokens() : 4096;
        if (maxTokens < 1) {
            logger.warn("maxTokens {} is less than 1", maxTokens);
        } else if (maxTokens > 32768) {
            logger.warn("maxTokens {} exceeds maximum 32768", maxTokens);
        }
        
        logger.info("Creating OpenAI compatible model: baseUrl={}, modelName={}, timeout={}s, temperature={}, maxTokens={}", 
            baseUrl, modelName, timeoutSeconds, temperature, maxTokens);
        
        // 直接创建 OpenAiChatModel
        try {
            var builder = OpenAiChatModel.builder()
                .modelName(modelName)
                .temperature(temperature.doubleValue())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxTokens(maxTokens);
            
            // 设置 API Key
            String finalApiKey = null;
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                finalApiKey = apiKey;
            } else {
                // 如果没有 API Key，尝试从环境变量读取（用于 OpenAI 官方 API）
                String envApiKey = System.getenv("OPENAI_API_KEY");
                if (envApiKey != null && !envApiKey.trim().isEmpty()) {
                    finalApiKey = envApiKey;
                    logger.debug("Using API key from environment variable");
                }
            }
            
            // 如果是 OpenAI 官方地址，必须要有有效的 API Key
            if (isOpenAiOfficialUrl) {
                if (finalApiKey == null || finalApiKey.trim().isEmpty()) {
                    logger.error("API key is required for OpenAI official API (baseUrl: {}). Please set apiKey in config or OPENAI_API_KEY environment variable.", baseUrl);
                    return null;
                }
                builder.apiKey(finalApiKey);
            } else {
                // 对于自定义 baseUrl（如 Ollama），如果没有 API Key，使用占位符
                if (finalApiKey != null && !finalApiKey.trim().isEmpty()) {
                    builder.apiKey(finalApiKey);
                } else {
                    // LangChain4j 强制要求 apiKey 不能为空，对于不需要 key 的兼容服务（如 Ollama），使用占位符
                    // 注意：不能使用 "demo"，因为 LangChain4j 会将其识别为特殊值并尝试连接到 langchain4j.dev
                    logger.warn("No API key provided for custom baseUrl. Using placeholder 'not-required' to satisfy OpenAiChatModel validation.");
                    builder.apiKey("not-required");
                }
                // 设置自定义 baseUrl
                builder.baseUrl(baseUrl);
                logger.info("Using custom base URL: {}", baseUrl);
            }
            
            return builder.build();
            
        } catch (Exception e) {
            logger.error("Failed to create OpenAI compatible model: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取模型配置（不创建实例）
     */
    public java.util.Optional<LlmModelConfig> getModelConfig(String modelId) {
        if (llmModelConfigService == null) {
            return java.util.Optional.empty();
        }
        return llmModelConfigService.getModelByModelId(modelId);
    }
    
    /**
     * 清除模型缓存（当配置更新时调用）
     */
    public void clearCache(String modelId) {
        if (modelId != null) {
            modelCache.remove(modelId);
            logger.info("Cleared cache for model_id: {}", modelId);
        } else {
            modelCache.clear();
            logger.info("Cleared all model cache");
        }
    }
}
