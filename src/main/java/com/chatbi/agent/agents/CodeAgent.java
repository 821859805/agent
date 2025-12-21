package com.chatbi.agent.agents;

import com.chatbi.agent.chatbi_server_java.service.LlmModelFactoryService;
import com.chatbi.agent.main.Brain;
import com.chatbi.agent.tools.BaseTool;
import com.chatbi.agent.tools.FinalAnswerTool;
import com.chatbi.agent.tools.SkillTool;
import com.chatbi.agent.tools.ThinkTool;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.AgentState;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.types.StreamChunk;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeAgent - 基于代码思考的Agent
 * 
 * CodeAgent是一个"用代码思考"的Agent，使用Python代码进行推理和行动。
 * 它扩展了BaseAgent，提供以下特性：
 * 
 * 核心特性：
 * 1. 基于代码的推理：使用Python代码而不是JSON进行推理
 * 2. 自我反思能力：使用"think"工具进行自我反思
 * 3. ReAct循环：Reason-Act-Observe（推理-行动-观察）循环
 * 4. 错误处理：更好的错误恢复和反思触发
 * 
 * 使用示例：
 *     CodeAgent agent = new CodeAgent(llmFactory, "gpt-4");
 *     agent.setup().join();
 *     Object result = agent.runAsync("计算斐波那契数列的前10项").join();
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class CodeAgent extends BaseAgent {
    
    /**
     * 思考引擎
     */
    private ThinkingEngine thinkingEngine;
    
    /**
     * 是否启用反思
     */
    private Boolean enableReflection = true;
    
    /**
     * 单个代码块的最大长度
     */
    private Integer maxCodeLength = 2000;
    
    /**
     * 是否使用异步执行器
     */
    private Boolean useAsyncExecutor = true;
    
    /**
     * 是否启用状态跟踪
     */
    private Boolean enableStateTracking = false;
    
    /**
     * 对话历史
     */
    private List<java.util.Map<String, Object>> conversationHistory = new ArrayList<>();
    
    /**
     * 对话上下文限制
     */
    private Integer conversationContextLimit = 10;
    
    // ==================== 构造函数 ====================
    
    public CodeAgent() {
        super();
        this.name = "code_agent";
        this.thinkingEngine = new ThinkingEngine(this);
    }
    
    public CodeAgent(LlmModelFactoryService llmModelFactoryService) {
        super(llmModelFactoryService);
        this.name = "code_agent";
        this.thinkingEngine = new ThinkingEngine(this);
    }
    
    public CodeAgent(LlmModelFactoryService llmModelFactoryService, String llmModelId) {
        super(llmModelFactoryService, llmModelId);
        this.name = "code_agent";
        this.thinkingEngine = new ThinkingEngine(this);
    }
    
    public CodeAgent(String name, Brain brain, List<BaseTool> tools) {
        super(name, brain, tools);
        this.thinkingEngine = new ThinkingEngine(this);
    }
    
    // ==================== 生命周期方法 ====================
    
    @Override
    public CompletableFuture<Void> setup() {
        if (isSetup) {
            return CompletableFuture.completedFuture(null);
        }
        
        return super.setup().thenRun(() -> {
            // 添加默认工具
            addTool(new FinalAnswerTool());
            addTool(new ThinkTool());
            
            // 设置代码思考提示词
            if (systemPrompt == null) {
                systemPrompt = buildCodeAgentSystemPrompt();
            }
            if (brain != null) {
                brain.setSystemPrompt(systemPrompt);
            }
            
            log.info("CodeAgent {} setup completed with {} tools", name, tools.size());
        });
    }
    
    // ==================== 执行方法 ====================
    
    @Override
    protected CompletableFuture<AgentResponse> executeStep(AgentState state) {
        this.state = state;
        
        Input inputData = state.getInput();
        if (inputData == null) {
            throw new IllegalArgumentException("No input found in state");
        }
        
        // 检查是否需要反思
        if (enableReflection && thinkingEngine != null && thinkingEngine.shouldReflect(state)) {
            performReflection(state);
        }
        
        // 增强输入以使用代码思考模式
        Input enhancedInput = enhanceInputForCodeThinking(inputData);
        state.setInput(enhancedInput);
        
        // 执行步骤
        brain.setState(state);
        return brain.step(state, llmModelId)
            .thenApply(result -> {
                // 检查响应中是否有代码需要处理
                if (result.getContent() != null && containsCodeBlocks(result.getContent())) {
                    return processCodeResponse(result);
                }
                return result;
            });
    }
    
    /**
     * 增强输入以使用代码思考模式
     */
    private Input enhanceInputForCodeThinking(Input inputData) {
        // 确保route设置为'code'
        if (inputData.getRoute() == null || inputData.getRoute().isEmpty()) {
            inputData.setRoute("code");
        }
        return inputData;
    }
    
    /**
     * 检查文本是否包含代码块
     */
    private boolean containsCodeBlocks(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("```python") || text.contains("```py") || 
               text.contains("<end_code>") || text.contains("```");
    }
    
    /**
     * 处理包含代码的响应
     */
    private AgentResponse processCodeResponse(AgentResponse response) {
        String content = response.getContent();
        List<String> codeBlocks = extractCodeBlocks(content);
        
        if (codeBlocks.isEmpty()) {
            return response;
        }
        
        StringBuilder processedContent = new StringBuilder(content);
        
        for (int i = 0; i < codeBlocks.size(); i++) {
            String code = codeBlocks.get(i);
            
            if (code.length() > maxCodeLength) {
                processedContent.append(String.format(
                    "\n**Observation:** Code block %d too long (max %d characters).\n",
                    i + 1, maxCodeLength
                ));
                continue;
            }
            
            // 检查是否调用了final_answer
            if (code.contains("final_answer(") || code.contains("final_answer (")) {
                // 提取final_answer的参数
                String answer = extractFinalAnswerArg(code);
                if (answer != null) {
                    state.setIsFinalAnswer(true);
                    state.setFinalAnswerValue(answer);
                    response.setIsFinalAnswer(true);
                    response.setTerminated(true);
                    response.setAnswer(answer);
                    processedContent.append(String.format(
                        "\n**Final Answer Found:** %s\n**Task Status:** COMPLETED\n",
                        answer
                    ));
                }
            }
            
            // 记录代码执行（在Java中我们不实际执行Python代码）
            processedContent.append(String.format(
                "\n**Observation:** Code block %d recorded.\n",
                i + 1
            ));
        }
        
        response.setContent(processedContent.toString());
        return response;
    }
    
    /**
     * 提取代码块
     */
    private List<String> extractCodeBlocks(String text) {
        List<String> codeBlocks = new ArrayList<>();
        
        if (text == null) {
            return codeBlocks;
        }
        
        // 匹配 ```python...```<end_code> 格式
        Pattern pattern1 = Pattern.compile("```(?:python|py)?\\s*\\n(.*?)\\n```<end_code>", Pattern.DOTALL);
        Matcher matcher1 = pattern1.matcher(text);
        while (matcher1.find()) {
            String code = matcher1.group(1).trim();
            if (!code.isEmpty()) {
                codeBlocks.add(code);
            }
        }
        
        // 匹配 ```python...```格式
        Pattern pattern2 = Pattern.compile("```(?:python|py)?\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher matcher2 = pattern2.matcher(text);
        while (matcher2.find()) {
            String code = matcher2.group(1).trim();
            if (!code.isEmpty() && !codeBlocks.contains(code)) {
                codeBlocks.add(code);
            }
        }
        
        return codeBlocks;
    }
    
    /**
     * 提取final_answer的参数
     */
    private String extractFinalAnswerArg(String code) {
        // 匹配 final_answer(...) 格式
        Pattern pattern = Pattern.compile("final_answer\\s*\\(\\s*['\"]?([^'\"\\)]+)['\"]?\\s*\\)");
        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // 匹配变量形式 final_answer(result)
        Pattern pattern2 = Pattern.compile("final_answer\\s*\\(\\s*(\\w+)\\s*\\)");
        Matcher matcher2 = pattern2.matcher(code);
        if (matcher2.find()) {
            return "[Variable: " + matcher2.group(1) + "]";
        }
        
        return null;
    }
    
    // ==================== 反思功能 ====================
    
    /**
     * 执行反思
     */
    private void performReflection(AgentState state) {
        if (thinkingEngine == null) {
            return;
        }
        
        String reflectionPrompt = thinkingEngine.generateReflection(state);
        state.setReflectionCount(state.getReflectionCount() + 1);
        state.setLastReflectionStep(state.getStepCount());
        
        // 使用think工具记录反思
        BaseTool thinkTool = getTool("think");
        if (thinkTool != null) {
            thinkTool.call(reflectionPrompt);
        }
        
        log.debug("Performed reflection at step {}", state.getStepCount());
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 构建CodeAgent的系统提示词
     */
    private String buildCodeAgentSystemPrompt() {
        return """
            You are an AI assistant that thinks and solves problems using Python code.
            
            ## How to Work
            1. **Think step by step**: Before writing code, explain your reasoning.
            2. **Write Python code**: Use code to solve problems, make calculations, or process data.
            3. **Use tools**: You have access to various tools. Call them as Python functions.
            4. **Provide final answer**: When you have solved the problem, use `final_answer(result)` to provide your answer.
            
            ## Code Format
            Write your code in Python code blocks like this:
            ```python
            # Your code here
            result = calculate_something()
            final_answer(result)
            ```<end_code>
            
            ## Guidelines
            - Always explain your thinking before writing code
            - Use clear variable names
            - Handle errors gracefully
            - When the task is complete, always call final_answer() with your result
            
            ## Available Tools
            You can call tools as Python functions. For example:
            - `final_answer(answer)` - Provide the final answer to the problem
            - `think(thought)` - Record your thoughts for reflection
            """;
    }
    
    /**
     * 解决问题（便捷方法）
     */
    public CompletableFuture<String> solveProblem(String problem) {
        Input inputObj = new Input(problem, "code");
        return runAsync(problem, null, maxSteps, false, "code")
            .thenApply(result -> result != null ? result.toString() : "");
    }
    
    // ==================== 流式执行方法 ====================
    
    /**
     * 流式执行 - 类似于 Python CodeMinion 的 execute_stream
     * 
     * 实现 Thought → Code → Observation 迭代循环，
     * 每个步骤都通过 chunkConsumer 回调实时输出
     * 
     * @param query 用户查询
     * @param maxSteps 最大步骤数
     * @param chunkConsumer 流式输出回调
     * @return 最终的 AgentResponse
     */
    public CompletableFuture<AgentResponse> runAsyncStream(
            String query, 
            Integer maxSteps,
            Consumer<StreamChunk> chunkConsumer) {
        
        int iterations = maxSteps != null ? maxSteps : this.maxSteps;
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeStreamLoop(query, iterations, chunkConsumer);
            } catch (Exception e) {
                log.error("Error in stream execution", e);
                chunkConsumer.accept(StreamChunk.error(e.getMessage()));
                return AgentResponse.builder()
                        .success(false)
                        .error(e.getMessage())
                        .build();
            }
        });
    }
    
    /**
     * 执行流式迭代循环 - Thought → Code → Observation
     */
    private AgentResponse executeStreamLoop(
            String query, 
            int maxIterations, 
            Consumer<StreamChunk> chunkConsumer) {
        
        String error = "";
        List<Map<String, String>> history = new ArrayList<>();
        
        // 初始化状态
        Input inputObj = new Input(query, "code");
        AgentState localState = new AgentState(inputObj);
        this.state = localState;
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            long stepStartTime = System.currentTimeMillis();
            
            // 1. 发送步骤开始事件
            chunkConsumer.accept(StreamChunk.stepStart(iteration, maxIterations));
            
            try {
                // 2. 构建增强的输入（包含历史和错误信息）
                String enhancedQuery = buildEnhancedQuery(query, error, history);
                inputObj.setQuery(enhancedQuery);
                localState.setInput(inputObj);
                
                // 3. 发送思考开始事件
                chunkConsumer.accept(StreamChunk.builder()
                        .content("💭 Thinking...")
                        .chunkType(StreamChunk.ChunkType.THOUGHT)
                        .build());
                
                // 4. 调用 Brain 获取 LLM 响应
                AgentResponse llmResponse = brain.step(localState, llmModelId).join();
                String responseContent = llmResponse.getContent();
                
                // 5. 发送 LLM 输出
                chunkConsumer.accept(StreamChunk.llmOutput(responseContent));
                
                // 6. 解析响应 - 提取代码块
                List<String> codeBlocks = extractCodeBlocks(responseContent);
                
                if (!codeBlocks.isEmpty()) {
                    for (String code : codeBlocks) {
                        // 发送代码执行事件
                        chunkConsumer.accept(StreamChunk.codeExecution(code));
                        
                        // 7. 执行代码/工具调用
                        ExecutionResult execResult = executeCode(code, chunkConsumer);
                        
                        // 8. 发送观察结果
                        chunkConsumer.accept(StreamChunk.observation(
                                execResult.output, execResult.success));
                        
                        // 9. 检查是否是最终答案
                        if (execResult.isFinalAnswer) {
                            chunkConsumer.accept(StreamChunk.finalAnswer(execResult.value));
                            
                            // 发送步骤结束
                            long duration = System.currentTimeMillis() - stepStartTime;
                            chunkConsumer.accept(StreamChunk.stepEnd(iteration, duration));
                            
                            return AgentResponse.builder()
                                    .answer(execResult.value)
                                    .content(responseContent)
                                    .isFinalAnswer(true)
                                    .terminated(true)
                                    .success(true)
                                    .build();
                        }
                        
                        // 10. 更新历史和错误状态
                        error = execResult.success ? "" : execResult.output;
                    }
                }
                
                // 添加本轮对话到历史
                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", responseContent);
                history.add(assistantMsg);
                
                // 发送步骤结束
                long duration = System.currentTimeMillis() - stepStartTime;
                chunkConsumer.accept(StreamChunk.stepEnd(iteration, duration));
                
            } catch (Exception e) {
                log.error("Error in iteration {}", iteration, e);
                error = e.getMessage();
                chunkConsumer.accept(StreamChunk.error(error));
            }
        }
        
        // 达到最大迭代次数
        String errorMsg = "Maximum iterations reached without final answer";
        chunkConsumer.accept(StreamChunk.error(errorMsg));
        
        return AgentResponse.builder()
                .answer(errorMsg)
                .success(false)
                .error(errorMsg)
                .build();
    }
    
    /**
     * 构建增强的查询（包含历史和错误信息）
     */
    private String buildEnhancedQuery(String query, String error, List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder();
        
        // 添加历史记录
        if (!history.isEmpty()) {
            sb.append("Previous conversation:\n");
            for (Map<String, String> msg : history) {
                sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
            }
            sb.append("\n");
        }
        
        // 添加错误信息
        if (error != null && !error.isEmpty()) {
            sb.append("Previous error: ").append(error).append("\n\n");
        }
        
        // 添加当前查询
        sb.append(query);
        
        return sb.toString();
    }
    
    /**
     * 执行代码或工具调用
     */
    private ExecutionResult executeCode(String code, Consumer<StreamChunk> chunkConsumer) {
        ExecutionResult result = new ExecutionResult();
        
        // 检查是否调用了 final_answer
        if (code.contains("final_answer(") || code.contains("final_answer (")) {
            result.isFinalAnswer = true;
            result.value = extractFinalAnswerArg(code);
            result.output = "Final answer provided: " + result.value;
            result.success = true;
            return result;
        }
        
        // 检查是否是工具调用
        for (BaseTool tool : tools) {
            String toolName = tool.getName();
            Pattern pattern = Pattern.compile(
                    toolName + "\\s*\\(\\s*['\"]?([^'\"\\)]*)['\"]?\\s*\\)", 
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(code);
            
            if (matcher.find()) {
                String argValue = matcher.group(1).trim();
                
                // 发送工具调用事件
                Map<String, Object> args = new HashMap<>();
                args.put("value", argValue);
                chunkConsumer.accept(StreamChunk.toolCall(toolName, args));
                
                try {
                    // 执行工具
                    Object toolResult = tool.call(argValue);
                    result.output = toolResult != null ? toolResult.toString() : "Tool executed successfully";
                    result.success = true;
                    
                    // 发送工具响应
                    chunkConsumer.accept(StreamChunk.toolResponse(toolName, result.output, true));
                    
                    // 特殊处理 Skill 工具
                    if (tool instanceof SkillTool && toolResult instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> skillResult = (Map<String, Object>) toolResult;
                        if (Boolean.TRUE.equals(skillResult.get("success"))) {
                            String skillPrompt = (String) skillResult.get("prompt");
                            if (skillPrompt != null) {
                                chunkConsumer.accept(StreamChunk.skillLoading(
                                        (String) skillResult.get("skill_name")));
                                result.output = "Skill loaded:\n" + skillPrompt;
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    log.error("Error executing tool: {}", toolName, e);
                    result.output = "Error: " + e.getMessage();
                    result.success = false;
                    chunkConsumer.accept(StreamChunk.toolResponse(toolName, result.output, false));
                }
                
                return result;
            }
        }
        
        // 没有匹配的工具调用，记录代码
        result.output = "Code recorded for execution.";
        result.success = true;
        
        return result;
    }
    
    /**
     * 执行结果内部类
     */
    private static class ExecutionResult {
        String output = "";
        String value = "";
        boolean success = false;
        boolean isFinalAnswer = false;
    }
    
    @Override
    protected Boolean isDone(AgentResponse result, AgentState state) {
        // 首先检查父类的判断
        if (super.isDone(result, state)) {
            return true;
        }
        
        // 检查状态中的final_answer标志
        if (state.getIsFinalAnswer() != null && state.getIsFinalAnswer()) {
            return true;
        }
        
        return false;
    }
    
    @Override
    protected Object finalize(AgentResponse result, AgentState state) {
        // 优先使用final_answer_value
        if (state.getFinalAnswerValue() != null) {
            return state.getFinalAnswerValue();
        }
        
        return super.finalize(result, state);
    }
    
    // ==================== 内部类：思考引擎 ====================
    
    /**
     * 思考引擎 - 管理反思策略和触发条件
     */
    @Data
    public static class ThinkingEngine {
        private CodeAgent agent;
        private int errorCountTrigger = 3;
        private int stepCountTrigger = 5;
        private double lowConfidenceTrigger = 0.3;
        
        public ThinkingEngine(CodeAgent agent) {
            this.agent = agent;
        }
        
        /**
         * 判断是否应该进行反思
         */
        public boolean shouldReflect(AgentState state) {
            // 错误计数达到阈值
            if (state.getErrorCount() >= errorCountTrigger) {
                return true;
            }
            
            // 步数达到阈值
            if (state.getStepCount() > 0 && state.getStepCount() % stepCountTrigger == 0) {
                return true;
            }
            
            // 置信度低于阈值
            if (state.getLastConfidence() < lowConfidenceTrigger) {
                return true;
            }
            
            return false;
        }
        
        /**
         * 生成反思提示
         */
        public String generateReflection(AgentState state) {
            String task = state.getTask() != null ? state.getTask() : "";
            int historySize = state.getHistory() != null ? state.getHistory().size() : 0;
            int errorCount = state.getErrorCount();
            
            return String.format("""
                Let me think about the current situation:
                
                **Task**: %s
                
                **Progress so far**: %d steps completed
                **Errors encountered**: %d
                
                **Reflection questions**:
                1. Am I making progress toward the goal?
                2. Are there any patterns in my errors?
                3. Should I try a different approach?
                4. What have I learned so far?
                5. What should I do next?
                
                Let me analyze this step by step...
                """, task, historySize, errorCount);
        }
    }
    
    @Override
    public String toString() {
        return String.format("CodeAgent[name=%s, tools=%d, reflection=%s]", 
            name, tools.size(), enableReflection);
    }
}

