package com.chatbi.agent.main;

import com.chatbi.agent.chatbi_server_java.service.LlmModelFactoryService;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.AgentState;
import com.chatbi.agent.types.History;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.tools.BaseTool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Brain - Agent系统的核心推理引擎
 * 
 * Brain是Agent系统的核心组件，负责：
 * 1. 管理多个LLM提供者（主LLM和专用LLM）
 * 2. 管理工具集合
 * 3. 管理不同的心智（left_mind、right_mind、hippocampus_mind）
 * 4. 处理输入和输出
 * 5. 支持流式输出
 * 6. 管理Python执行环境
 */
@Slf4j
@Data
public class Brain {
    /**
     * Brain的唯一标识符
     */
    private String id;
    
    /**
     * 心智字典，键为心智ID，值为Mind实例
     */
    private Map<String, Mind> minds = new HashMap<>();
    
    /**
     * 主LLM模型ID
     */
    private String llmModelId;
    
    /**
     * 专用LLM字典，键为名称，值为模型ID
     */
    private Map<String, String> llms = new HashMap<>();
    
    /**
     * LLM模型工厂服务
     */
    private LlmModelFactoryService llmModelFactoryService;
    
    /**
     * 工具列表
     */
    private List<BaseTool> tools = new ArrayList<>();
    
    /**
     * Agent状态引用
     */
    private AgentState state;
    
    /**
     * 系统提示词
     */
    private String systemPrompt;
    
    // ==================== 构造函数 ====================
    
    public Brain() {
        this.id = UUID.randomUUID().toString();
        initializeDefaultMinds();
    }
    
    public Brain(LlmModelFactoryService llmModelFactoryService) {
        this();
        this.llmModelFactoryService = llmModelFactoryService;
    }
    
    public Brain(LlmModelFactoryService llmModelFactoryService, String llmModelId) {
        this(llmModelFactoryService);
        this.llmModelId = llmModelId;
    }
    
    // ==================== 初始化方法 ====================
    
    /**
     * 初始化默认心智
     */
    private void initializeDefaultMinds() {
        // 左脑：逻辑推理和分析思维
        addMind(new Mind(
            "left_mind",
            "I'm the left mind, adept at logical reasoning and analytical thinking. " +
            "I excel in tasks involving mathematics, language, and detailed analysis. " +
            "My capabilities include: solving complex mathematical problems, " +
            "understanding and processing language with precision, " +
            "performing logical reasoning and critical thinking, " +
            "analyzing and synthesizing information systematically.",
            this
        ));
        
        // 右脑：创造性和艺术性思维
        addMind(new Mind(
            "right_mind",
            "I'm the right mind, flourishing in creative and artistic tasks. " +
            "I thrive in activities that involve imagination, intuition, and holistic thinking. " +
            "My capabilities include: creating and appreciating art and music, " +
            "engaging in creative problem-solving and innovation, " +
            "understanding and interpreting emotions and expressions, " +
            "thinking in a non-linear and abstract manner.",
            this
        ));
        
        // 海马体心智：记忆形成和检索
        addMind(new Mind(
            "hippocampus_mind",
            "I'm the hippocampus mind, specializing in memory formation, organization, and retrieval. " +
            "I play a crucial role in both the storage of new memories and the recall of past experiences. " +
            "My capabilities include: forming and consolidating new memories, " +
            "organizing and structuring information for easy retrieval, " +
            "facilitating the recall of past experiences and learned information.",
            this
        ));
    }
    
    // ==================== 心智管理 ====================
    
    /**
     * 添加心智
     */
    public void addMind(Mind mind) {
        minds.put(mind.getId(), mind);
        mind.setBrain(this);
    }
    
    /**
     * 获取心智
     */
    public Mind getMind(String mindId) {
        return minds.get(mindId);
    }
    
    /**
     * 选择合适的心智
     */
    public String chooseMind(Input input) {
        // 目前直接返回left_mind，未来可以根据输入内容智能选择
        return "left_mind";
    }
    
    // ==================== 工具管理 ====================
    
    /**
     * 添加工具
     */
    public void addTool(BaseTool tool) {
        tools.add(tool);
    }
    
    /**
     * 获取工具
     */
    public BaseTool getTool(String name) {
        return tools.stream()
            .filter(t -> t.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
    
    // ==================== LLM访问 ====================
    
    /**
     * 获取主LLM实例
     */
    public ChatLanguageModel getLlm() {
        if (llmModelFactoryService == null) {
            log.warn("LlmModelFactoryService is not available");
            return null;
        }
        if (llmModelId != null && !llmModelId.isEmpty()) {
            return llmModelFactoryService.getOrCreateModel(llmModelId);
        }
        return llmModelFactoryService.getDefaultModel();
    }
    
    /**
     * 根据模型ID获取LLM实例
     */
    public ChatLanguageModel getLlmByModelId(String modelId) {
        if (llmModelFactoryService == null) {
            log.warn("LlmModelFactoryService is not available");
            return null;
        }
        if (modelId != null && !modelId.isEmpty()) {
            return llmModelFactoryService.getOrCreateModel(modelId);
        }
        return getLlm();
    }
    
    // ==================== LLM 配置访问（用于 LmpActionNode） ====================
    
    /**
     * 获取 LLM 的 Base URL
     * 类似 minion 中获取 llm 配置的方式
     */
    public String getBaseUrl() {
        if (llmModelFactoryService == null) {
            return null;
        }
        String modelId = llmModelId != null ? llmModelId : "default";
        return llmModelFactoryService.getModelConfig(modelId)
                .map(config -> config.getBaseUrl())
                .orElse("http://localhost:11434/v1/");
    }
    
    /**
     * 获取 API Key
     */
    public String getApiKey() {
        if (llmModelFactoryService == null) {
            return null;
        }
        String modelId = llmModelId != null ? llmModelId : "default";
        return llmModelFactoryService.getModelConfig(modelId)
                .map(config -> config.getApiKey())
                .orElse(null);
    }
    
    /**
     * 获取模型名称
     */
    public String getModelName() {
        if (llmModelFactoryService == null) {
            return llmModelId;
        }
        String modelId = llmModelId != null ? llmModelId : "default";
        return llmModelFactoryService.getModelConfig(modelId)
                .map(config -> config.getModelId())
                .orElse(llmModelId);
    }
    
    /**
     * 获取温度参数
     */
    public double getTemperature() {
        if (llmModelFactoryService == null) {
            return 0.7;
        }
        String modelId = llmModelId != null ? llmModelId : "default";
        return llmModelFactoryService.getModelConfig(modelId)
                .map(config -> config.getTemperature() != null 
                        ? config.getTemperature().doubleValue() 
                        : 0.7)
                .orElse(0.7);
    }
    
    // ==================== 核心执行方法 ====================
    
    /**
     * 执行一步推理
     * 
     * @param state Agent状态
     * @param modelId 可选的模型ID
     * @return AgentResponse
     */
    public CompletableFuture<AgentResponse> step(AgentState state, String modelId) {
        return step(state, modelId, null);
    }
    
    /**
     * 执行一步推理（完整参数）
     */
    public CompletableFuture<AgentResponse> step(AgentState state, String modelId, Map<String, Object> kwargs) {
        // 更新内部状态引用
        this.state = state;
        
        // 获取输入
        Input input = state.getInput();
        if (input == null) {
            throw new IllegalArgumentException("State must contain input");
        }
        
        // 设置工具和系统提示
        // 始终使用brain.tools的最新副本，避免工具重复或使用过时的工具列表
        // 这样可以确保每次step()调用时都使用最新的工具列表
        input.setTools(new ArrayList<>(this.tools));
        if (systemPrompt != null && input.getSystemPrompt() == null) {
            input.setSystemPrompt(systemPrompt);
        }
        
        // 选择心智
        String mindId = input.getMindId();
        if (mindId == null || mindId.isEmpty()) {
            mindId = chooseMind(input);
        }
        Mind mind = minds.get(mindId);
        if (mind == null) {
            mind = minds.get("left_mind"); // 默认使用左脑
        }
        
        // 获取LLM
        ChatLanguageModel llm = modelId != null ? getLlmByModelId(modelId) : getLlm();
        
        // 执行心智步骤
        log.debug("Executing brain step with mind: {}", mindId);
        return mind.step(input, llm);
    }
    
    /**
     * 同步执行
     */
    public AgentResponse run(String query) {
        return run(query, null);
    }
    
    /**
     * 同步执行（带额外参数）
     */
    public AgentResponse run(String query, Map<String, Object> kwargs) {
        Input input = new Input(query);
        AgentState state = new AgentState(input);
        return step(state, null, kwargs).join();
    }
    
    /**
     * 异步执行
     */
    public CompletableFuture<AgentResponse> runAsync(String query) {
        return runAsync(query, null);
    }
    
    /**
     * 异步执行（带额外参数）
     */
    public CompletableFuture<AgentResponse> runAsync(String query, Map<String, Object> kwargs) {
        Input input = new Input(query);
        AgentState state = new AgentState(input);
        return step(state, null, kwargs);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 构建提示词
     */
    public String buildPrompt(Input input) {
        StringBuilder prompt = new StringBuilder();
        
        // 系统提示
        if (input.getSystemPrompt() != null) {
            prompt.append(input.getSystemPrompt()).append("\n\n");
        }
        
        // 指令
        if (input.getInstruction() != null && !input.getInstruction().isEmpty()) {
            prompt.append("Instruction: ").append(input.getInstruction()).append("\n\n");
        }
        
        // 工具信息
        if (tools != null && !tools.isEmpty()) {
            prompt.append("Available tools:\n");
            for (BaseTool tool : tools) {
                prompt.append("- ").append(tool.getName())
                      .append(": ").append(tool.getDescription()).append("\n");
            }
            prompt.append("\n");
        }
        
        // 查询
        String queryStr = input.getQueryString();
        if (queryStr != null && !queryStr.isEmpty()) {
            prompt.append("Query: ").append(queryStr).append("\n");
        }
        
        return prompt.toString();
    }
    
    /**
     * 格式化工具列表为提示词
     */
    public String formatToolsForPrompt() {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools:\n\n");
        
        for (BaseTool tool : tools) {
            sb.append("### ").append(tool.getName()).append("\n");
            sb.append(tool.getDescription()).append("\n");
            
            if (tool.getInputs() != null && !tool.getInputs().isEmpty()) {
                sb.append("Parameters:\n");
                for (Map.Entry<String, Map<String, Object>> entry : tool.getInputs().entrySet()) {
                    sb.append("  - ").append(entry.getKey());
                    Map<String, Object> paramInfo = entry.getValue();
                    if (paramInfo.get("type") != null) {
                        sb.append(" (").append(paramInfo.get("type")).append(")");
                    }
                    if (paramInfo.get("description") != null) {
                        sb.append(": ").append(paramInfo.get("description"));
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return String.format("Brain[id=%s, minds=%d, tools=%d]", 
            id, minds.size(), tools.size());
    }
}
