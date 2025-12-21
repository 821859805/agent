package com.chatbi.agent.minion;

import com.chatbi.agent.actions.LmpActionNode;
import com.chatbi.agent.executor.PythonExecutor;
import com.chatbi.agent.main.Brain;
import com.chatbi.agent.message.MessageBuilder;
import com.chatbi.agent.tools.BaseTool;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.types.StreamChunk;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeMinion - 代码执行 Minion
 * 
 * 完全模仿 Python minion 中的 CodeMinion，使用：
 * 1. LmpActionNode 调用 LLM（类似 minion 的 node.execute(messages, tools=tools)）
 * 2. client.chat.completions.create() 风格的 API 调用
 * 3. 工具调用 (Function Calling) 处理
 * 4. Thought → Code → Observation 迭代循环
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class CodeMinion extends WorkerMinion {
    
    /**
     * 最大迭代次数
     */
    private int maxIterations = 5;
    
    /**
     * Python 执行器
     */
    private PythonExecutor pythonExecutor;
    
    /**
     * LmpActionNode - 类似 minion 的 LmpActionNode(self.get_llm())
     */
    private LmpActionNode actionNode;
    
    /**
     * 当前轮次的尝试记录
     */
    private List<Map<String, Object>> currentTurnAttempts = new ArrayList<>();
    
    /**
     * 系统提示词
     */
    private static final String SYSTEM_PROMPT = """
        You are an AI assistant that solves problems using Python code.
        
        ## How to Work
        1. **Think**: Explain your reasoning before writing code.
        2. **Code**: Write Python code to solve the problem.
        3. **Observe**: Check the execution result.
        4. **Iterate**: If needed, refine your approach.
        5. **Answer**: Use `final_answer(result)` when done.
        
        ## Code Format
        Write your code in Python code blocks like this:
        ```python
        # Your code here
        result = calculate_something()
        final_answer(result)
        ```<end_code>
        
        ## Available Tools
        You can call tools as Python functions:
        %s
        
        ## Important
        - Always explain your thinking before writing code
        - End code blocks with <end_code>
        - Use final_answer() to provide the final result
        - Handle errors gracefully
        
        Let's start!
        """;
    
    // ==================== 构造函数 ====================
    
    public CodeMinion() {
        super();
        this.pythonExecutor = new PythonExecutor();
    }
    
    public CodeMinion(Input input, Brain brain) {
        super(input, brain);
        this.pythonExecutor = new PythonExecutor();
        
        // 注入工具到 Python 执行器
        pythonExecutor.sendTools(getTools());
        
        // 初始化 LmpActionNode（类似 minion 的 node = LmpActionNode(self.get_llm())）
        initActionNode();
    }
    
    public CodeMinion(Input input, Brain brain, Map<String, Object> task) {
        super(input, brain, task);
        this.pythonExecutor = new PythonExecutor();
        pythonExecutor.sendTools(getTools());
        initActionNode();
    }
    
    /**
     * 初始化 LmpActionNode
     * 类似 minion 的：node = LmpActionNode(self.get_llm())
     */
    private void initActionNode() {
        if (brain != null) {
            String baseUrl = brain.getBaseUrl();
            String apiKey = brain.getApiKey();
            String model = brain.getModelName();
            double temperature = brain.getTemperature();
            
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = "http://localhost:11434/v1/"; // 默认 Ollama
            }
            if (model == null || model.isEmpty()) {
                model = "qwen3-max";
            }
            
            this.actionNode = new LmpActionNode(baseUrl, apiKey, model, temperature);
            log.info("LmpActionNode initialized: baseUrl={}, model={}", baseUrl, model);
        }
    }
    
    // ==================== 执行方法 ====================
    
    @Override
    public CompletableFuture<AgentResponse> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeLoop(null);
            } catch (Exception e) {
                log.error("Error in CodeMinion execution", e);
                return AgentResponse.builder()
                        .success(false)
                        .error(e.getMessage())
                        .build();
            }
        });
    }
    
    @Override
    public void executeStream(Consumer<StreamChunk> chunkConsumer) {
        try {
            executeLoop(chunkConsumer);
        } catch (Exception e) {
            log.error("Error in CodeMinion stream execution", e);
            chunkConsumer.accept(StreamChunk.error(e.getMessage()));
        }
    }
    
    /**
     * 执行 Thought → Code → Observation 循环
     * 
     * 完全模仿 minion 的 CodeMinion.execute() 方法
     */
    private AgentResponse executeLoop(Consumer<StreamChunk> chunkConsumer) throws Exception {
        currentTurnAttempts.clear();
        String error = "";
        String query = getQuery();
        List<BaseTool> tools = getTools();
        
        // 检查 ActionNode
        if (actionNode == null) {
            initActionNode();
        }
        if (actionNode == null) {
            String errMsg = "LmpActionNode not initialized. Brain configuration may be missing.";
            log.error(errMsg);
            if (chunkConsumer != null) {
                chunkConsumer.accept(StreamChunk.error(errMsg));
            }
            return AgentResponse.error(errMsg);
        }
        
        log.info("CodeMinion starting with {} tools, query: {}", tools.size(), query);
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            long stepStart = System.currentTimeMillis();
            
            // 1. 发送步骤开始
            if (chunkConsumer != null) {
                chunkConsumer.accept(StreamChunk.stepStart(iteration, maxIterations));
            }
            
            try {
                // 2. 构建消息（类似 minion 的 construct_messages_with_history）
                List<Map<String, Object>> messages = buildMessages(query, tools, error);
                
                // 3. 调用 LLM（类似 minion 的 response = await node.execute(messages, tools=tools)）
                if (chunkConsumer != null) {
                    chunkConsumer.accept(StreamChunk.thought("💭 Calling LLM with tools..."));
                }
                
                List<String> stopSequences = List.of("<end_code>");
                LmpActionNode.ExecuteResult result = actionNode.executeSync(messages, tools, stopSequences);
                
                if (!result.isSuccess()) {
                    error = result.getError();
                    log.error("LLM call failed: {}", error);
                    if (chunkConsumer != null) {
                        chunkConsumer.accept(StreamChunk.error(error));
                    }
                    continue;
                }
                
                String responseContent = result.getContent();
                log.debug("LLM response: {}", responseContent);
                
                // 4. 处理响应
                if (responseContent != null && !responseContent.isEmpty()) {
                    if (chunkConsumer != null) {
                        chunkConsumer.accept(StreamChunk.llmOutput(responseContent));
                    }
                    
                    // 添加到历史
                    currentTurnAttempts.add(Map.of(
                            "role", "assistant",
                            "content", responseContent
                    ));
                }
                
                // 5. 检查是否有 final_answer 工具调用
                if (result.isFinalAnswer()) {
                    String finalAnswer = result.getFinalAnswer();
                    
                    if (chunkConsumer != null) {
                        chunkConsumer.accept(StreamChunk.finalAnswer(finalAnswer));
                        long duration = System.currentTimeMillis() - stepStart;
                        chunkConsumer.accept(StreamChunk.stepEnd(iteration, duration));
                    }
                    
                    log.info("Final answer from tool call: {}", finalAnswer);
                    
                    return AgentResponse.builder()
                            .answer(finalAnswer)
                            .content(responseContent)
                            .rawResponse(responseContent)
                            .isFinalAnswer(true)
                            .terminated(true)
                            .success(true)
                            .build();
                }
                
                // 6. 处理其他工具调用
                if (result.hasToolCalls()) {
                    for (LmpActionNode.ToolCallInfo toolCall : result.getToolCalls()) {
                        if (chunkConsumer != null) {
                            chunkConsumer.accept(StreamChunk.toolCall(
                                    toolCall.getName(), toolCall.getArguments()));
                            chunkConsumer.accept(StreamChunk.observation(
                                    toolCall.getResult(), true));
                        }
                        
                        // 添加工具结果到历史
                        currentTurnAttempts.add(Map.of(
                                "role", "tool",
                                "tool_call_id", toolCall.getId(),
                                "content", toolCall.getResult()
                        ));
                    }
                }
                
                // 7. 解析代码块并执行
                String code = extractCode(responseContent);
                
                if (code != null && !code.isEmpty()) {
                    // 发送代码执行事件
                    if (chunkConsumer != null) {
                        chunkConsumer.accept(StreamChunk.codeExecution(code));
                    }
                    
                    // 执行代码
                    PythonExecutor.ExecutionResult execResult = pythonExecutor.execute(code);
                    
                    // 发送观察结果
                    if (chunkConsumer != null) {
                        chunkConsumer.accept(StreamChunk.observation(
                                execResult.getOutput(), execResult.isSuccess()));
                    }
                    
                    // 检查是否是最终答案
                    if (execResult.isFinalAnswer()) {
                        String finalAnswer = execResult.getFinalAnswerValue();
                        
                        if (chunkConsumer != null) {
                            chunkConsumer.accept(StreamChunk.finalAnswer(finalAnswer));
                            long duration = System.currentTimeMillis() - stepStart;
                            chunkConsumer.accept(StreamChunk.stepEnd(iteration, duration));
                        }
                        
                        log.info("Final answer from code execution: {}", finalAnswer);
                        
                        return AgentResponse.builder()
                                .answer(finalAnswer)
                                .content(responseContent)
                                .rawResponse(responseContent)
                                .isFinalAnswer(true)
                                .terminated(true)
                                .success(true)
                                .build();
                    }
                    
                    // 更新错误状态
                    error = execResult.isSuccess() ? "" : execResult.getError();
                    
                    // 添加观察结果到历史
                    currentTurnAttempts.add(Map.of(
                            "role", "user",
                            "content", "Observation: " + execResult.getOutput()
                    ));
                }
                
                // 发送步骤结束
                if (chunkConsumer != null) {
                    long duration = System.currentTimeMillis() - stepStart;
                    chunkConsumer.accept(StreamChunk.stepEnd(iteration, duration));
                }
                
            } catch (Exception e) {
                log.error("Error in iteration {}", iteration, e);
                error = e.getMessage();
                if (chunkConsumer != null) {
                    chunkConsumer.accept(StreamChunk.error(error));
                }
            }
        }
        
        // 达到最大迭代次数
        String errorMsg = "Maximum iterations reached without final answer";
        if (chunkConsumer != null) {
            chunkConsumer.accept(StreamChunk.error(errorMsg));
        }
        
        return AgentResponse.builder()
                .answer(errorMsg)
                .success(false)
                .error(errorMsg)
                .build();
    }
    
    // ==================== 消息构建 ====================
    
    /**
     * 构建消息列表
     * 
     * 类似 minion 的 construct_messages_with_history，
     * 返回 OpenAI 格式: [{"role": "...", "content": "..."}]
     */
    private List<Map<String, Object>> buildMessages(String query, List<BaseTool> tools, String error) {
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // 1. 系统消息
        String systemPrompt = String.format(SYSTEM_PROMPT, getToolsDescription(tools));
        messages.add(Map.of("role", "system", "content", systemPrompt));
        
        // 2. 添加历史消息
        messages.addAll(currentTurnAttempts);
        
        // 3. 构建用户消息
        StringBuilder userContent = new StringBuilder();
        
        // 添加任务信息
        if (task != null && !task.isEmpty()) {
            String instruction = (String) task.getOrDefault("instruction", "");
            String description = (String) task.getOrDefault("task_description", "");
            
            if (!instruction.isEmpty()) {
                userContent.append("Task: ").append(instruction).append("\n\n");
            }
            if (!description.isEmpty()) {
                userContent.append("Description: ").append(description).append("\n\n");
            }
        }
        
        // 添加查询
        userContent.append("Query: ").append(query);
        
        // 添加错误反馈
        if (error != null && !error.isEmpty()) {
            userContent.append("\n\n**Previous Error:**\n").append(error);
            userContent.append("\n\nPlease fix the error and try again.");
        }
        
        userContent.append("\n\nRemember to end your code blocks with <end_code>.");
        
        messages.add(Map.of("role", "user", "content", userContent.toString()));
        
        return messages;
    }
    
    /**
     * 获取工具描述
     */
    private String getToolsDescription(List<BaseTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (BaseTool tool : tools) {
            sb.append(String.format("- %s: %s\n", tool.getName(), tool.getDescription()));
        }
        sb.append("- final_answer(answer): Provide the final answer to the problem\n");
        sb.append("- think(thought): Record your thoughts for reflection\n");
        return sb.toString();
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 提取代码块
     */
    private String extractCode(String response) {
        if (response == null) return null;
        
        Pattern pattern = Pattern.compile(
                "```(?:python|py)?\\s*\\n([\\s\\S]*?)\\n```(?:<end_code>)?", 
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(response);
        
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        return null;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (pythonExecutor != null) {
            pythonExecutor.cleanup();
        }
    }
}
