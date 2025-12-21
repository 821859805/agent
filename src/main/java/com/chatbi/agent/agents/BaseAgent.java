package com.chatbi.agent.agents;

import com.chatbi.agent.chatbi_server_java.service.LlmModelFactoryService;
import com.chatbi.agent.main.Brain;
import com.chatbi.agent.types.*;
import com.chatbi.agent.tools.BaseTool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Agent基类
 * 
 * 定义所有Agent的基本接口，支持完整的生命周期管理。
 * 这是Minion框架中所有Agent的基础类，提供了统一的接口和通用功能。
 * 
 * 核心特性：
 * - 工具管理：自动处理普通工具、工具集、状态感知工具
 * - 多LLM支持：支持主LLM和多个专用LLM
 * - 状态管理：使用强类型AgentState管理执行状态
 * - 流式输出：支持实时流式返回执行过程
 * - 自动压缩：当上下文接近上限时自动压缩历史记录
 */
@Slf4j
@Data
public class BaseAgent {
    // ==================== 基础配置 ====================
    /**
     * Agent名称
     */
    protected String name = "base_agent";
    
    /**
     * 工具列表
     */
    protected List<BaseTool> tools = new ArrayList<>();
    
    /**
     * Brain推理引擎
     */
    protected Brain brain;
    
    // ==================== LLM配置 ====================
    /**
     * 主LLM的modelId
     */
    protected String llmModelId;
    
    /**
     * 专用LLM字典
     */
    protected Map<String, String> llms = new HashMap<>();
    
    /**
     * 系统提示词
     */
    protected String systemPrompt;
    
    /**
     * LLM模型工厂服务
     */
    protected LlmModelFactoryService llmModelFactoryService;
    
    // ==================== 状态管理 ====================
    /**
     * Agent状态
     */
    protected AgentState state;
    
    /**
     * 用户ID
     */
    protected String userId;
    
    /**
     * Agent ID
     */
    protected String agentId = UUID.randomUUID().toString();
    
    /**
     * 会话ID
     */
    protected String sessionId = UUID.randomUUID().toString();
    
    /**
     * 最大执行步数
     */
    protected Integer maxSteps = 20;
    
    // ==================== 自动压缩配置 ====================
    protected Boolean autoCompactEnabled = true;
    protected Double autoCompactThreshold = 0.92;
    protected Integer autoCompactKeepRecent = 10;
    protected Integer defaultContextWindow = 128000;
    protected String compactModel;
    
    // ==================== 内部状态 ====================
    protected Boolean isSetup = false;
    protected List<Object> toolsets = new ArrayList<>();
    
    // ==================== 构造函数 ====================
    
    public BaseAgent() {
        this.state = new AgentState();
        this.state.setAgent(this);
    }

    public BaseAgent(LlmModelFactoryService llmModelFactoryService) {
        this();
        this.llmModelFactoryService = llmModelFactoryService;
    }

    public BaseAgent(LlmModelFactoryService llmModelFactoryService, String llmModelId) {
        this(llmModelFactoryService);
        this.llmModelId = llmModelId;
    }
    
    public BaseAgent(String name, Brain brain, List<BaseTool> tools) {
        this();
        this.name = name;
        this.brain = brain;
        if (tools != null) {
            this.tools.addAll(tools);
        }
    }

    // ==================== LLM访问方法 ====================
    
    /**
     * 获取主LLM实例
     */
    protected ChatLanguageModel getLlm() {
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
     * 根据modelId获取LLM实例
     */
    protected ChatLanguageModel getLlmByModelId(String modelId) {
        if (llmModelFactoryService == null) {
            log.warn("LlmModelFactoryService is not available");
            return null;
        }
        if (modelId != null && !modelId.isEmpty()) {
            return llmModelFactoryService.getOrCreateModel(modelId);
        }
        return getLlm();
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化Agent，设置工具和Brain
     */
    public CompletableFuture<Void> setup() {
        if (isSetup) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // 初始化Brain
                if (brain == null) {
                    brain = new Brain(llmModelFactoryService, llmModelId);
                }
                // 创建工具列表的副本，避免BaseAgent.tools和brain.tools共享同一个引用
                brain.setTools(new ArrayList<>(tools));
                if (systemPrompt != null) {
                    brain.setSystemPrompt(systemPrompt);
                }

                // 转换原始函数为工具
                convertRawFunctionsToTools();

                // 包装状态感知工具
                wrapStateAwareTools();

                isSetup = true;
                log.info("Agent {} setup completed with {} tools", name, tools.size());
            } catch (Exception e) {
                log.error("Failed to setup agent", e);
                throw new RuntimeException("Agent setup failed", e);
            }
        });
    }

    /**
     * 清理并关闭Agent
     */
    public CompletableFuture<Void> close() {
        if (!isSetup) {
            log.warn("Agent {} not setup, skipping cleanup", name);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            log.info("Closing agent {}", name);
            toolsets.clear();
            isSetup = false;
            log.info("Agent {} cleanup completed", name);
        });
    }

    // ==================== 执行方法 ====================

    /**
     * 运行完整任务
     */
    public CompletableFuture<Object> runAsync(String task) {
        return runAsync(task, null, null, false, null);
    }
    
    /**
     * 运行完整任务（带参数）
     */
    public CompletableFuture<Object> runAsync(String task, Integer maxSteps) {
        return runAsync(task, null, maxSteps, false, null);
    }

    /**
     * 运行完整任务（完整参数）
     */
    public CompletableFuture<Object> runAsync(String task, AgentState state, Integer maxSteps, 
                                              Boolean reset, String route) {
        ensureSetup();

        // 处理状态初始化
        if (state == null) {
            if (task == null) {
                throw new IllegalArgumentException("Either 'task' or 'state' must be provided");
            }
            initStateFromTask(task, route);
        } else {
            this.state = state;
            if (task != null) {
                this.state.setTask(task);
                if (this.state.getInput() != null) {
                    this.state.getInput().setQuery(task);
                }
            }
        }

        if (reset != null && reset) {
            this.state.reset();
            initStateFromTask(task, route);
        }

        Integer stepsToUse = maxSteps != null ? maxSteps : this.maxSteps;
        return runComplete(this.state, stepsToUse);
    }
    
    /**
     * 同步运行
     */
    public Object run(String task) {
        return runAsync(task).join();
    }
    
    /**
     * 同步运行（带参数）
     */
    public Object run(String task, Integer maxSteps) {
        return runAsync(task, maxSteps).join();
    }

    /**
     * 执行单步决策/行动
     */
    public CompletableFuture<AgentResponse> step(AgentState state) {
        ensureSetup();

        if (state.getInput() == null) {
            throw new IllegalArgumentException("State must contain input object");
        }

        Input inputObj = state.getInput();

        // 预处理输入
        Input processedInput = preStep(inputObj);
        state.setInput(processedInput);

        // 执行主要步骤
        return executeStep(state)
            .thenApply(result -> {
                // 后处理结果
                postStep(processedInput, result);
                return result;
            });
    }

    /**
     * 执行实际的步骤操作
     */
    protected CompletableFuture<AgentResponse> executeStep(AgentState state) {
        // 同步state到brain
        brain.setState(state);

        // 调用brain.step
        return brain.step(state, llmModelId);
    }

    /**
     * step执行前的预处理
     */
    protected Input preStep(Input inputData) {
        return inputData;
    }

    /**
     * step执行后的后处理
     */
    protected void postStep(Input inputData, AgentResponse result) {
        // 默认不执行任何操作
    }

    // ==================== 执行循环 ====================

    /**
     * 执行完整的步骤循环
     */
    protected CompletableFuture<Object> runComplete(AgentState state, Integer maxSteps) {
        return runCompleteRecursive(state, maxSteps, 0);
    }

    /**
     * 递归执行步骤
     */
    private CompletableFuture<Object> runCompleteRecursive(AgentState currentState, 
                                                           Integer maxSteps, Integer stepCount) {
        log.debug("Executing step {}/{}", stepCount + 1, maxSteps);
        
        if (stepCount >= maxSteps) {
            log.warn("Reached max steps ({}), providing final answer", maxSteps);
            try {
                Object finalAnswer = provideFinalAnswer(currentState);
                return CompletableFuture.completedFuture(finalAnswer);
            } catch (Exception e) {
                log.error("Failed to provide final answer", e);
                throw new RuntimeException("Task execution reached max steps and is still incomplete", e);
            }
        }

        return step(currentState)
            .thenCompose(stepResult -> {
                log.debug("Step {} completed, checking if done...", stepCount + 1);
                
                if (isDone(stepResult, currentState)) {
                    log.info("Task completed at step {}", stepCount + 1);
                    Object result = finalize(stepResult, currentState);
                    return CompletableFuture.completedFuture(result);
                }

                log.debug("Task not done, continuing to next step...");
                AgentState nextState = updateState(currentState, stepResult);
                return runCompleteRecursive(nextState, maxSteps, stepCount + 1);
            });
    }

    // ==================== 状态管理 ====================

    /**
     * 更新状态
     */
    protected AgentState updateState(AgentState state, AgentResponse result) {
        this.state = state;

        // 添加到历史记录
        Map<String, Object> message = convertResultToMessage(result);
        state.getHistory().append(message);
        state.setStepCount(state.getStepCount() + 1);

        // 更新查询（如果任务未完成）
        if (state.getInput() != null && !isDone(result, state)) {
            state.getInput().setQuery("Continue your task: " + state.getTask());
        }

        return state;
    }

    /**
     * 转换结果为消息格式
     */
    protected Map<String, Object> convertResultToMessage(AgentResponse result) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", result.getContent() != null ? result.getContent() : "");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("score", result.getScore());
        metadata.put("confidence", result.getConfidence());
        metadata.put("terminated", result.getTerminated());
        metadata.put("is_final_answer", result.getIsFinalAnswer());

        if (result.getAnswer() != null) {
            metadata.put("answer", result.getAnswer());
        }
        if (result.getError() != null) {
            metadata.put("error", result.getError());
        }

        message.put("metadata", metadata);
        return message;
    }

    /**
     * 判断任务是否完成
     */
    protected Boolean isDone(AgentResponse result, AgentState state) {
        // 检查终止标志
        if (result.getTerminated() != null && result.getTerminated()) {
            return true;
        }
        if (result.getIsFinalAnswer() != null && result.getIsFinalAnswer()) {
            return true;
        }
        if (state.getIsFinalAnswer() != null && state.getIsFinalAnswer()) {
            return true;
        }
        
        // 检查响应内容
        String content = result.getContent();
        if (content != null) {
            String lowerContent = content.toLowerCase();
            if ((lowerContent.contains("final answer") || 
                 lowerContent.contains("最终答案") ||
                 lowerContent.contains("答案：") ||
                 lowerContent.contains("答案是")) &&
                !lowerContent.contains("continue") && 
                !lowerContent.contains("下一步") &&
                content.length() > 10) {
                return true;
            }
        }
        
        // 检查答案
        if (result.getAnswer() != null && !result.getAnswer().isEmpty()) {
            String answer = result.getAnswer().toLowerCase();
            if (!answer.contains("continue") && 
                !answer.contains("下一步") &&
                answer.length() > 5) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 整理最终结果
     */
    protected Object finalize(AgentResponse result, AgentState state) {
        if (state.getFinalAnswerValue() != null) {
            return state.getFinalAnswerValue();
        }
        if (result.getAnswer() != null) {
            return result.getAnswer();
        }
        if (result.getContent() != null) {
            return result.getContent();
        }
        return result;
    }

    /**
     * 提供最终答案（达到最大步数时）
     */
    protected Object provideFinalAnswer(AgentState state) {
        String task = state.getTask() != null ? state.getTask() : "";
        String finalAnswerPrompt = String.format(
            "You have reached the maximum step limit, but the task is not yet complete.\n\n" +
            "Original task: %s\n\n" +
            "You have executed %d steps. Based on the current progress, " +
            "please provide the best possible final answer or conclusion.",
            task, state.getStepCount()
        );

        if (state.getInput() != null) {
            state.getInput().setQuery(finalAnswerPrompt);
        }

        try {
            AgentResponse result = step(state).join();
            if (result.getTerminated() == null || !result.getTerminated()) {
                result.setTerminated(true);
            }
            return result.getAnswer() != null ? result.getAnswer() : result.getContent();
        } catch (Exception e) {
            log.error("获取最终答案失败", e);
            return String.format(
                "The task could not be completed within the maximum step limit, " +
                "but %d steps have been executed.",
                state.getStepCount()
            );
        }
    }

    /**
     * 从任务初始化状态
     */
    protected void initStateFromTask(String task, String route) {
        Input inputObj = new Input(task);
        if (route != null && !route.isEmpty()) {
            inputObj.setRoute(route);
        }
        this.state = new AgentState();
        this.state.setAgent(this);
        this.state.setInput(inputObj);
        this.state.setTask(task);
        this.state.setHistory(new History());
    }

    // ==================== 工具管理 ====================

    /**
     * 添加工具
     */
    public void addTool(BaseTool tool) {
        this.tools.add(tool);
        if (brain != null) {
            brain.addTool(tool);
        }
    }

    /**
     * 获取工具
     */
    public BaseTool getTool(String toolName) {
        return tools.stream()
            .filter(tool -> tool.getName().equals(toolName))
            .findFirst()
            .orElse(null);
    }

    /**
     * 转换原始函数为工具
     */
    protected void convertRawFunctionsToTools() {
        // Java中通常不需要此功能，工具已经是BaseTool实例
    }

    /**
     * 包装状态感知工具
     */
    protected void wrapStateAwareTools() {
        // 为需要状态的工具设置状态访问
        for (BaseTool tool : tools) {
            if (tool.getNeedsState() != null && tool.getNeedsState()) {
                log.debug("Tool {} is state-aware", tool.getName());
            }
        }
    }

    /**
     * 确保Agent已初始化
     */
    protected void ensureSetup() {
        if (!isSetup) {
            throw new IllegalStateException("Agent " + name + " not setup. Call setup() first.");
        }
    }
    
    @Override
    public String toString() {
        return String.format("Agent[name=%s, tools=%d, setup=%s]", 
            name, tools.size(), isSetup);
    }
}
