package com.chatbi.agent.main;

import com.chatbi.agent.actions.LmpActionNode;
import com.chatbi.agent.message.MessageBuilder;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.tools.BaseTool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chatbi.agent.types.StreamChunk;

/**
 * 心智类 - 负责执行具体的推理步骤
 * 
 * 使用 LmpActionNode 调用 LLM，类似 minion 的方式：
 * - node = LmpActionNode(self.get_llm())
 * - response = await node.execute(messages, tools=tools)
 * 
 * 每个Mind代表一种不同的推理策略或思维方式
 */
@Slf4j
@Data
public class Mind {
    /**
     * 心智ID
     */
    private String id;
    
    /**
     * 心智描述
     */
    private String description;
    
    /**
     * Brain实例引用
     */
    private Brain brain;
    
    /**
     * LmpActionNode 实例
     */
    private LmpActionNode actionNode;
    
    // ==================== 构造函数 ====================
    
    public Mind(String id, String description, Brain brain) {
        this.id = id;
        this.description = description;
        this.brain = brain;
    }
    
    public Mind(String id, String description) {
        this(id, description, null);
    }
    
    // ==================== 执行方法 ====================
    
    /**
     * 初始化 LmpActionNode
     */
    private void initActionNode() {
        if (brain != null && actionNode == null) {
            String baseUrl = brain.getBaseUrl();
            String apiKey = brain.getApiKey();
            String model = brain.getModelName();
            double temperature = brain.getTemperature();
            
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = "http://localhost:11434/v1/";
            }
            if (model == null || model.isEmpty()) {
                model = "qwen3-max";
            }
            
            this.actionNode = new LmpActionNode(baseUrl, apiKey, model, temperature);
            log.debug("LmpActionNode initialized for mind {}: baseUrl={}, model={}", id, baseUrl, model);
        }
    }
    
    /**
     * 执行推理步骤（使用 LmpActionNode）
     * 
     * 类似于 minion 中的：
     * node = LmpActionNode(self.get_llm())
     * response = await node.execute(messages, tools=tools)
     * 
     * @param input 输入对象
     * @param selectedLlm 选择的 LLM（备用，主要使用 LmpActionNode）
     * @param tools 可用的工具列表
     * @return AgentResponse
     */
    public CompletableFuture<AgentResponse> step(Input input, ChatLanguageModel selectedLlm, List<BaseTool> tools) {
        // 初始化 ActionNode
        initActionNode();
        
        if (actionNode == null) {
            log.warn("LmpActionNode not available, returning error");
            return CompletableFuture.completedFuture(
                    AgentResponse.error("LmpActionNode not initialized. Brain configuration may be missing."));
        }

        // 构建消息列表（OpenAI 格式）
        List<Map<String, Object>> messages = buildMessages(input, tools);

        // 异步执行 LLM 调用
        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                
                log.debug("Calling LmpActionNode with {} messages and {} tools", messages.size(), tools.size());
                
                // 调用 LmpActionNode（类似 minion 的 node.execute(messages, tools=tools)）
                LmpActionNode.ExecuteResult result = actionNode.executeSync(messages, tools, null);
                
                long duration = System.currentTimeMillis() - startTime;
                log.debug("LmpActionNode call completed in {}ms", duration);
                
                // 处理响应
                AgentResponse agentResponse = processResult(result, input);
                agentResponse.setExecutionTime(duration);
                
                return agentResponse;
            } catch (Exception e) {
                log.error("Error executing mind step", e);
                return AgentResponse.error(e.getMessage());
            }
        });
    }
    
    /**
     * 执行推理步骤（简化版，无工具）
     */
    public CompletableFuture<AgentResponse> step(Input input, ChatLanguageModel selectedLlm) {
        List<BaseTool> tools = new ArrayList<>();
        if (input.getTools() != null) {
            for (Object tool : input.getTools()) {
                if (tool instanceof BaseTool) {
                    tools.add((BaseTool) tool);
                }
            }
        }
        if (brain != null && brain.getTools() != null) {
            tools.addAll(new ArrayList<>(brain.getTools()));
        }
        return step(input, selectedLlm, tools);
    }
    
    /**
     * 流式执行推理步骤
     */
    public CompletableFuture<AgentResponse> stepStream(
            Input input, 
            ChatLanguageModel selectedLlm, 
            List<BaseTool> tools,
            Consumer<StreamChunk> chunkConsumer) {
        
        return step(input, selectedLlm, tools).thenApply(response -> {
            if (response.hasToolCalls()) {
                for (AgentResponse.ToolCallInfo toolCall : response.getToolCalls()) {
                    chunkConsumer.accept(StreamChunk.toolCall(
                            toolCall.getName(), 
                            toolCall.getArguments()));
                }
            }
            
            if (response.getContent() != null) {
                chunkConsumer.accept(StreamChunk.llmOutput(response.getContent()));
            }
            
            if (response.getIsFinalAnswer() != null && response.getIsFinalAnswer()) {
                chunkConsumer.accept(StreamChunk.finalAnswer(response.getAnswer()));
            }
            
            return response;
        });
    }
    
    // ==================== 消息构建 ====================
    
    /**
     * 构建消息列表（OpenAI 格式）
     * 返回 [{"role": "...", "content": "..."}]
     */
    private List<Map<String, Object>> buildMessages(Input input, List<BaseTool> tools) {
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // 添加系统消息
        String systemContent = buildSystemPrompt(input, tools);
        if (systemContent != null && !systemContent.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemContent));
        }
        
        // 添加用户消息
        String userContent = buildUserPrompt(input);
        messages.add(Map.of("role", "user", "content", userContent));
        
        return messages;
    }
    
    /**
     * 构建系统提示词（包含工具描述）
     */
    private String buildSystemPrompt(Input input, List<BaseTool> tools) {
        StringBuilder prompt = new StringBuilder();
        
        // 基础系统提示
        if (input.getSystemPrompt() != null) {
            prompt.append(input.getSystemPrompt()).append("\n\n");
        }
        
        // 心智描述
        prompt.append("You are a helpful AI assistant. ").append(description).append("\n\n");
        
        // 工具信息
        if (tools != null && !tools.isEmpty()) {
            prompt.append("## Available Tools\n\n");
            prompt.append("You have access to the following tools. Use them via function calling:\n\n");
            
            for (BaseTool tool : tools) {
                prompt.append("### ").append(tool.getName()).append("\n");
                prompt.append(tool.getDescription()).append("\n");
                
                Map<String, Map<String, Object>> inputs = tool.getInputs();
                if (inputs != null && !inputs.isEmpty()) {
                    prompt.append("Parameters:\n");
                    for (Map.Entry<String, Map<String, Object>> entry : inputs.entrySet()) {
                        String paramName = entry.getKey();
                        Map<String, Object> paramDef = entry.getValue();
                        
                        if (paramDef != null && !paramDef.isEmpty()) {
                            String paramDesc = (String) paramDef.getOrDefault("description", "");
                            String paramType = (String) paramDef.getOrDefault("type", "string");
                            prompt.append(String.format("  - %s (%s): %s\n", paramName, paramType, paramDesc));
                        } else {
                            prompt.append(String.format("  - %s\n", paramName));
                        }
                    }
                }
                prompt.append("\n");
            }
            
            // 添加 final_answer 工具说明
            prompt.append("### final_answer\n");
            prompt.append("Provide the final answer to the problem. ");
            prompt.append("Use this when you have completed the task.\n");
            prompt.append("Parameters:\n");
            prompt.append("  - answer (string): The final answer to return\n\n");
            
            prompt.append("## Guidelines\n");
            prompt.append("1. Think step by step before providing your answer.\n");
            prompt.append("2. Use tools via function calling when necessary.\n");
            prompt.append("3. When the task is complete, use the final_answer tool.\n");
        }
        
        return prompt.toString();
    }
    
    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(Input input) {
        StringBuilder prompt = new StringBuilder();
        
        if (input.getLongContext() != null && !input.getLongContext().isEmpty()) {
            prompt.append("Context:\n").append(input.getLongContext()).append("\n\n");
        }
        
        if (input.getInstruction() != null && !input.getInstruction().isEmpty()) {
            prompt.append("Instruction: ").append(input.getInstruction()).append("\n\n");
        }
        
        String query = input.getQueryString();
        if (query != null && !query.isEmpty()) {
            prompt.append("Query: ").append(query);
        }
        
        return prompt.toString();
    }
    
    // ==================== 响应处理 ====================
    
    /**
     * 处理 LmpActionNode 的执行结果
     */
    private AgentResponse processResult(LmpActionNode.ExecuteResult result, Input input) {
        AgentResponse response = new AgentResponse();
        
        if (!result.isSuccess()) {
            response.setError(result.getError());
            response.setSuccess(false);
            return response;
        }
        
        response.setSuccess(true);
        response.setContent(result.getContent());
        response.setRawResponse(result.getContent());
        
        // 处理工具调用
        if (result.hasToolCalls()) {
            List<AgentResponse.ToolCallInfo> toolCalls = new ArrayList<>();
            for (LmpActionNode.ToolCallInfo tc : result.getToolCalls()) {
                toolCalls.add(AgentResponse.ToolCallInfo.builder()
                        .id(tc.getId())
                        .name(tc.getName())
                        .arguments(tc.getArguments())
                        .result(tc.getResult())
                        .build());
            }
            response.setToolCalls(toolCalls);
            response.setToolResults(result.getToolResults());
        }
        
        // 处理最终答案
        if (result.isFinalAnswer()) {
            response.setAnswer(result.getFinalAnswer());
            response.setIsFinalAnswer(true);
            response.setTerminated(true);
        }
        
        // 提取思考内容
        String content = result.getContent();
        if (content != null) {
            String[] extracted = extractThinkAndAnswer(content);
            if (!extracted[0].isEmpty()) {
                response.setThinkContent(extracted[0]);
            }
            if (!extracted[1].isEmpty() && response.getAnswer() == null) {
                response.setAnswer(extracted[1]);
            }
        }
        
        response.setScore(1.0);
        response.setConfidence(1.0);
        
        return response;
    }
    
    /**
     * 提取思考内容和答案（支持DeepSeek思考模式）
     */
    private String[] extractThinkAndAnswer(String response) {
        if (response == null || response.isEmpty()) {
            return new String[]{"", ""};
        }
        
        Pattern thinkPattern = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);
        Matcher matcher = thinkPattern.matcher(response);
        
        String thinkContent = "";
        String answerContent = response;
        
        if (matcher.find()) {
            thinkContent = matcher.group(1).trim();
            answerContent = response.replaceAll("<think>.*?</think>", "").trim();
        }
        
        return new String[]{thinkContent, answerContent};
    }
    
    @Override
    public String toString() {
        return String.format("Mind[id=%s]", id);
    }
}
