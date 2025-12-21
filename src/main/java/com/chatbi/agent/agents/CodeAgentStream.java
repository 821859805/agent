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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeAgentStream - 支持流式输出的 CodeAgent
 * 
 * 类似于 Python minion 中的 CodeMinion，实现：
 * 1. Thought → Code → Observation 迭代循环
 * 2. 流式输出每个步骤的处理过程
 * 3. 工具调用和结果的实时输出
 * 4. Skill 的动态加载和执行
 * 
 * 工作流程：
 * 1. 接收用户查询
 * 2. 进入迭代循环：
 *    a. LLM 生成 Thought（思考）
 *    b. LLM 生成 Code（代码/工具调用）
 *    c. 执行代码/工具，获取 Observation（观察结果）
 *    d. 检查是否调用 final_answer()
 *    e. 如果没有，将 Observation 加入历史，继续下一轮
 * 3. 返回最终答案
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class CodeAgentStream extends CodeAgent {
    
    /**
     * 最大迭代次数
     */
    private int maxIterations = 5;
    
    /**
     * 当前轮次的尝试记录
     */
    private List<Map<String, String>> currentTurnAttempts = new ArrayList<>();
    
    /**
     * 工具执行器
     */
    private ToolExecutor toolExecutor;
    
    // ==================== 构造函数 ====================
    
    public CodeAgentStream() {
        super();
        this.toolExecutor = new ToolExecutor(this);
    }
    
    public CodeAgentStream(LlmModelFactoryService llmModelFactoryService) {
        super(llmModelFactoryService);
        this.toolExecutor = new ToolExecutor(this);
    }
    
    public CodeAgentStream(LlmModelFactoryService llmModelFactoryService, String llmModelId) {
        super(llmModelFactoryService, llmModelId);
        this.toolExecutor = new ToolExecutor(this);
    }
    
    public CodeAgentStream(String name, Brain brain, List<BaseTool> tools) {
        super(name, brain, tools);
        this.toolExecutor = new ToolExecutor(this);
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
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeStreamLoop(query, maxSteps != null ? maxSteps : maxIterations, chunkConsumer);
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
     * 执行流式迭代循环
     * 
     * 核心实现，类似于 Python CodeMinion 的 execute_stream 方法
     */
    private AgentResponse executeStreamLoop(
            String query, 
            int maxSteps, 
            Consumer<StreamChunk> chunkConsumer) {
        
        currentTurnAttempts.clear();
        String error = "";
        AgentResponse finalResponse = null;
        
        // 构建历史记录（之前轮次的内容）
        List<Map<String, String>> previousHistory = getHistoryMessages();
        
        for (int iteration = 0; iteration < maxSteps; iteration++) {
            long stepStartTime = System.currentTimeMillis();
            
            // 1. 发送步骤开始事件
            chunkConsumer.accept(StreamChunk.stepStart(iteration, maxSteps));
            
            try {
                // 2. 构建消息（包含历史记录）
                String fullPrompt = buildIterationPrompt(query, error, previousHistory);
                
                // 3. 调用 LLM 获取响应
                chunkConsumer.accept(StreamChunk.builder()
                        .content("💭 Thinking...")
                        .chunkType(StreamChunk.ChunkType.THOUGHT)
                        .build());
                
                AgentResponse llmResponse = brain.step(state, llmModelId).join();
                String responseContent = llmResponse.getContent();
                
                // 4. 发送 LLM 输出
                chunkConsumer.accept(StreamChunk.llmOutput(responseContent));
                
                // 5. 解析响应 - 提取 Thought 和 Code
                ParsedResponse parsed = parseResponse(responseContent);
                
                if (parsed.thought != null && !parsed.thought.isEmpty()) {
                    chunkConsumer.accept(StreamChunk.thought(parsed.thought));
                }
                
                // 6. 检查是否有代码/工具调用
                if (parsed.code != null && !parsed.code.isEmpty()) {
                    // 发送代码执行事件
                    chunkConsumer.accept(StreamChunk.codeExecution(parsed.code));
                    
                    // 7. 执行代码/工具调用
                    ExecutionResult execResult = executeCodeOrTools(parsed.code, chunkConsumer);
                    
                    // 8. 发送观察结果
                    chunkConsumer.accept(StreamChunk.observation(
                            execResult.output, execResult.success));
                    
                    // 9. 检查是否是最终答案
                    if (execResult.isFinalAnswer) {
                        chunkConsumer.accept(StreamChunk.finalAnswer(execResult.finalAnswerValue));
                        
                        finalResponse = AgentResponse.builder()
                                .answer(execResult.finalAnswerValue)
                                .content(responseContent)
                                .isFinalAnswer(true)
                                .terminated(true)
                                .success(true)
                                .build();
                        
                        // 发送步骤结束
                        long duration = System.currentTimeMillis() - stepStartTime;
                        chunkConsumer.accept(StreamChunk.stepEnd(iteration, duration));
                        
                        return finalResponse;
                    }
                    
                    // 10. 将观察结果添加到历史，用于下一轮迭代
                    error = execResult.success ? "" : execResult.output;
                    addToHistory("assistant", responseContent);
                    addToHistory("user", "Observation: " + execResult.output);
                }
                
                // 发送步骤结束
                long duration = System.currentTimeMillis() - stepStartTime;
                chunkConsumer.accept(StreamChunk.stepEnd(iteration, duration));
                
            } catch (Exception e) {
                log.error("Error in iteration {}", iteration, e);
                error = e.getMessage();
                chunkConsumer.accept(StreamChunk.error(error));
            }
        }
        
        // 达到最大迭代次数，返回错误
        String errorMsg = "Maximum iterations reached without final answer";
        chunkConsumer.accept(StreamChunk.error(errorMsg));
        
        return AgentResponse.builder()
                .answer(errorMsg)
                .success(false)
                .error(errorMsg)
                .build();
    }
    
    // ==================== 解析和执行方法 ====================
    
    /**
     * 解析 LLM 响应，提取 Thought 和 Code
     */
    private ParsedResponse parseResponse(String response) {
        ParsedResponse parsed = new ParsedResponse();
        
        if (response == null) {
            return parsed;
        }
        
        // 提取 Thought（思考部分）
        // 匹配 "Thought:" 或 "Let me think..." 等模式
        Pattern thoughtPattern = Pattern.compile(
                "(?:Thought:|Let me think|I'll|Let's|First,|Step \\d+:)([^`]*?)(?:```|$)", 
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher thoughtMatcher = thoughtPattern.matcher(response);
        if (thoughtMatcher.find()) {
            parsed.thought = thoughtMatcher.group(1).trim();
        }
        
        // 提取代码块
        Pattern codePattern = Pattern.compile(
                "```(?:python|py|javascript|js)?\\s*\\n([\\s\\S]*?)\\n```(?:<end_code>)?", 
                Pattern.DOTALL);
        Matcher codeMatcher = codePattern.matcher(response);
        if (codeMatcher.find()) {
            parsed.code = codeMatcher.group(1).trim();
        }
        
        // 提取工具调用（如果没有代码块）
        if (parsed.code == null || parsed.code.isEmpty()) {
            // 检查是否有工具调用模式
            Pattern toolPattern = Pattern.compile(
                    "(\\w+)\\s*\\(([^)]*)\\)", Pattern.DOTALL);
            Matcher toolMatcher = toolPattern.matcher(response);
            while (toolMatcher.find()) {
                String toolName = toolMatcher.group(1);
                if (isKnownTool(toolName)) {
                    parsed.code = toolMatcher.group(0);
                    break;
                }
            }
        }
        
        return parsed;
    }
    
    /**
     * 检查是否是已知的工具
     */
    private boolean isKnownTool(String name) {
        if (tools == null) return false;
        return tools.stream().anyMatch(t -> t.getName().equalsIgnoreCase(name));
    }
    
    /**
     * 执行代码或工具调用
     */
    private ExecutionResult executeCodeOrTools(String code, Consumer<StreamChunk> chunkConsumer) {
        ExecutionResult result = new ExecutionResult();
        
        // 检查是否调用了 final_answer
        if (code.contains("final_answer(") || code.contains("final_answer (")) {
            result.isFinalAnswer = true;
            result.finalAnswerValue = extractFinalAnswerValue(code);
            result.output = "Final answer provided: " + result.finalAnswerValue;
            result.success = true;
            return result;
        }
        
        // 检查是否是工具调用
        for (BaseTool tool : tools) {
            String toolName = tool.getName();
            Pattern pattern = Pattern.compile(toolName + "\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(code);
            
            if (matcher.find()) {
                String argsStr = matcher.group(1);
                
                // 发送工具调用事件
                Map<String, Object> args = parseToolArgs(argsStr);
                chunkConsumer.accept(StreamChunk.toolCall(toolName, args));
                
                try {
                    // 执行工具
                    Object toolResult = tool.callWithMap(args);
                    result.output = toolResult != null ? toolResult.toString() : "Tool executed successfully";
                    result.success = true;
                    
                    // 发送工具响应事件
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
                                // 将 skill prompt 添加到上下文
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
        result.output = "Code recorded. In a real environment, this would be executed.";
        result.success = true;
        
        return result;
    }
    
    /**
     * 提取 final_answer 的值
     */
    private String extractFinalAnswerValue(String code) {
        // 匹配 final_answer("...") 或 final_answer('...') 或 final_answer(variable)
        Pattern pattern = Pattern.compile(
                "final_answer\\s*\\(\\s*['\"]?([^'\"\\)]+)['\"]?\\s*\\)");
        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return code;
    }
    
    /**
     * 解析工具参数
     */
    private Map<String, Object> parseToolArgs(String argsStr) {
        Map<String, Object> args = new HashMap<>();
        
        if (argsStr == null || argsStr.trim().isEmpty()) {
            return args;
        }
        
        // 简单解析：key=value 或 "value" 格式
        // 对于只有一个参数的情况
        argsStr = argsStr.trim();
        if (argsStr.startsWith("\"") || argsStr.startsWith("'")) {
            // 单个字符串参数
            args.put("value", argsStr.replaceAll("^['\"]|['\"]$", ""));
        } else if (argsStr.contains("=")) {
            // key=value 格式
            String[] parts = argsStr.split(",");
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim().replaceAll("^['\"]|['\"]$", "");
                    args.put(key, value);
                }
            }
        } else {
            // 作为第一个参数
            args.put("skill", argsStr);
        }
        
        return args;
    }
    
    /**
     * 构建迭代提示词
     */
    private String buildIterationPrompt(String query, String error, List<Map<String, String>> history) {
        StringBuilder prompt = new StringBuilder();
        
        // 添加系统提示词（包含可用工具）
        prompt.append(buildCodeAgentSystemPromptWithTools());
        prompt.append("\n\n");
        
        // 添加历史记录
        if (history != null && !history.isEmpty()) {
            prompt.append("Previous conversation:\n");
            for (Map<String, String> msg : history) {
                prompt.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
            }
            prompt.append("\n");
        }
        
        // 添加错误信息（如果有）
        if (error != null && !error.isEmpty()) {
            prompt.append("Previous error: ").append(error).append("\n\n");
        }
        
        // 添加当前查询
        prompt.append("User query: ").append(query);
        
        return prompt.toString();
    }
    
    /**
     * 构建包含工具信息的系统提示词
     */
    private String buildCodeAgentSystemPromptWithTools() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
            You are an AI assistant that thinks and solves problems step by step.
            
            ## How to Work
            1. **Think step by step**: Before taking action, explain your reasoning.
            2. **Use available tools**: You have access to tools listed below. Call them to solve problems.
            3. **Observe results**: After each tool call, observe the result and decide next steps.
            4. **Provide final answer**: When done, use `final_answer(result)` to provide your answer.
            
            ## Available Tools
            """);
        
        for (BaseTool tool : tools) {
            prompt.append(String.format("\n### %s\n", tool.getName()));
            prompt.append(tool.getDescription()).append("\n");
            if (tool.getInputs() != null && !tool.getInputs().isEmpty()) {
                prompt.append("Parameters:\n");
                for (Map.Entry<String, Map<String, Object>> entry : tool.getInputs().entrySet()) {
                    prompt.append(String.format("  - %s: %s\n", 
                            entry.getKey(), 
                            entry.getValue().get("description")));
                }
            }
        }
        
        prompt.append("""
            
            ## Response Format
            Think step by step, then write code or call tools:
            
            Thought: [Your reasoning here]
            
            ```python
            # Your code or tool calls here
            result = tool_name(args)
            final_answer(result)
            ```<end_code>
            
            ## Important
            - Always provide clear reasoning before acting
            - Use final_answer() when you have the complete answer
            - If you encounter errors, analyze them and try a different approach
            """);
        
        return prompt.toString();
    }
    
    /**
     * 获取历史消息
     */
    private List<Map<String, String>> getHistoryMessages() {
        List<Map<String, String>> messages = new ArrayList<>();
        if (state != null && state.getHistory() != null) {
            // 转换历史记录格式
            // 这里需要根据实际的 History 实现来调整
        }
        return messages;
    }
    
    /**
     * 添加到历史记录
     */
    private void addToHistory(String role, String content) {
        Map<String, String> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        currentTurnAttempts.add(message);
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 解析后的响应
     */
    private static class ParsedResponse {
        String thought;
        String code;
    }
    
    /**
     * 执行结果
     */
    private static class ExecutionResult {
        String output = "";
        boolean success = false;
        boolean isFinalAnswer = false;
        String finalAnswerValue = "";
    }
    
    /**
     * 工具执行器
     */
    @Data
    public static class ToolExecutor {
        private CodeAgentStream agent;
        
        public ToolExecutor(CodeAgentStream agent) {
            this.agent = agent;
        }
    }
}

