package com.chatbi.agent.actions;

import com.chatbi.agent.message.ToolFormatter;
import com.chatbi.agent.tools.BaseTool;
import com.chatbi.agent.types.AgentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LmpActionNode - LLM Action 节点
 * 
 * 类似于 Python minion 中的 LmpActionNode，直接调用 OpenAI 兼容 API：
 * - client.chat.completions.create(messages, tools, ...)
 * - 处理 tool_calls 响应
 * - 执行工具并将结果回传
 * - 支持 final_answer 终止
 * 
 * 不再使用 LangChain4j 的 generate 方法，而是直接调用 HTTP API。
 */
@Slf4j
@Data
public class LmpActionNode {
    
    private String baseUrl;
    private String apiKey;
    private String model;
    private double temperature;
    private int maxTokens;
    private int timeoutSeconds;
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private OkHttpClient httpClient;
    
    // ==================== 构造函数 ====================
    
    public LmpActionNode(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = 0.7;
        this.maxTokens = 4096;
        this.timeoutSeconds = 120;
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }
    
    public LmpActionNode(String baseUrl, String apiKey, String model, double temperature) {
        this(baseUrl, apiKey, model);
        this.temperature = temperature;
    }
    
    // ==================== 执行方法 ====================
    
    /**
     * 执行 LLM 调用（类似 minion 的 node.execute(messages, tools=tools)）
     * 
     * @param messages OpenAI 格式的消息列表 [{"role": "...", "content": "..."}]
     * @param tools 工具列表
     * @param stopSequences 停止序列
     * @return LLM 响应文本或工具调用结果
     */
    public CompletableFuture<ExecuteResult> execute(
            List<Map<String, Object>> messages,
            List<BaseTool> tools,
            List<String> stopSequences) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeInternal(messages, tools, stopSequences);
            } catch (Exception e) {
                log.error("Error in LmpActionNode.execute", e);
                return ExecuteResult.error(e.getMessage());
            }
        });
    }
    
    /**
     * 同步执行（用于简单场景）
     */
    public ExecuteResult executeSync(
            List<Map<String, Object>> messages,
            List<BaseTool> tools,
            List<String> stopSequences) throws Exception {
        return executeInternal(messages, tools, stopSequences);
    }
    
    /**
     * 内部执行逻辑
     */
    private ExecuteResult executeInternal(
            List<Map<String, Object>> messages,
            List<BaseTool> tools,
            List<String> stopSequences) throws Exception {
        
        // 1. 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        
        // 添加消息
        ArrayNode messagesArray = objectMapper.valueToTree(messages);
        requestBody.set("messages", messagesArray);
        
        // 添加工具（如果有）
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = formatToolsForApi(tools);
            requestBody.set("tools", toolsArray);
            requestBody.put("tool_choice", "auto");
        }
        
        // 添加停止序列（如果有）
        if (stopSequences != null && !stopSequences.isEmpty()) {
            ArrayNode stopArray = objectMapper.createArrayNode();
            for (String stop : stopSequences) {
                stopArray.add(stop);
            }
            requestBody.set("stop", stopArray);
        }
        
        // 2. 发送请求
        String url = baseUrl + "chat/completions";
        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json")
        );
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json");
        
        // 添加 API Key（如果有）
        if (apiKey != null && !apiKey.isEmpty() && !"not-required".equals(apiKey)) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }
        
        Request request = requestBuilder.build();
        
        log.debug("Sending request to {}: {}", url, requestBody.toString());
        
        // 3. 执行请求
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new IOException("API request failed: " + response.code() + " - " + errorBody);
            }
            
            String responseBody = response.body().string();
            log.debug("API Response: {}", responseBody);
            
            // 4. 解析响应
            JsonNode responseJson = objectMapper.readTree(responseBody);
            return parseResponse(responseJson, messages, tools);
        }
    }
    
    /**
     * 解析 API 响应
     * 
     * 类似 minion 的：
     * - response.choices[0].message.content
     * - response.choices[0].message.tool_calls
     */
    private ExecuteResult parseResponse(
            JsonNode response, 
            List<Map<String, Object>> messages,
            List<BaseTool> tools) throws Exception {
        
        JsonNode choices = response.get("choices");
        if (choices == null || choices.isEmpty()) {
            return ExecuteResult.error("No choices in response");
        }
        
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            return ExecuteResult.error("No message in response");
        }
        
        // 获取文本内容
        String content = message.has("content") && !message.get("content").isNull() 
                ? message.get("content").asText() 
                : "";
        
        // 检查是否有工具调用
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            return handleToolCalls(toolCalls, content, messages, tools);
        }
        
        // 没有工具调用，返回文本内容
        return ExecuteResult.success(content);
    }
    
    /**
     * 处理工具调用
     * 
     * 类似 minion 的 _handle_tool_calls
     */
    private ExecuteResult handleToolCalls(
            JsonNode toolCalls,
            String content,
            List<Map<String, Object>> messages,
            List<BaseTool> tools) throws Exception {
        
        List<ToolCallInfo> toolCallInfos = new ArrayList<>();
        StringBuilder toolResults = new StringBuilder();
        
        for (JsonNode toolCall : toolCalls) {
            String toolCallId = toolCall.get("id").asText();
            JsonNode function = toolCall.get("function");
            String functionName = function.get("name").asText();
            String arguments = function.get("arguments").asText();
            
            log.info("Tool call: {} with args: {}", functionName, arguments);
            
            ToolCallInfo info = new ToolCallInfo();
            info.setId(toolCallId);
            info.setName(functionName);
            info.setArguments(arguments);
            
            // 检查是否是 final_answer
            if ("final_answer".equals(functionName)) {
                String answer = extractFinalAnswer(arguments);
                info.setResult(answer);
                info.setFinalAnswer(true);
                toolCallInfos.add(info);
                
                log.info("Final answer detected: {}", answer);
                
                return ExecuteResult.builder()
                        .success(true)
                        .content(content)
                        .toolCalls(toolCallInfos)
                        .finalAnswer(answer)
                        .isFinalAnswer(true)
                        .build();
            }
            
            // 检查是否是 think
            if ("think".equals(functionName)) {
                String thought = extractThought(arguments);
                info.setResult(thought);
                toolCallInfos.add(info);
                toolResults.append("Thought: ").append(thought).append("\n");
                continue;
            }
            
            // 执行其他工具
            String result = executeToolCall(functionName, arguments, tools);
            info.setResult(result);
            toolCallInfos.add(info);
            toolResults.append("Tool ").append(functionName).append(" result: ")
                    .append(result).append("\n");
        }
        
        // 如果有工具调用但没有 final_answer，返回工具结果
        return ExecuteResult.builder()
                .success(true)
                .content(content)
                .toolCalls(toolCallInfos)
                .toolResults(toolResults.toString())
                .isFinalAnswer(false)
                .build();
    }
    
    /**
     * 执行工具调用
     */
    private String executeToolCall(String toolName, String toolArgs, List<BaseTool> tools) {
        // 查找工具
        BaseTool targetTool = null;
        if (tools != null) {
            for (BaseTool tool : tools) {
                if (tool.getName().equals(toolName)) {
                    targetTool = tool;
                    break;
                }
            }
        }
        
        if (targetTool == null) {
            return "Error: Tool " + toolName + " not found";
        }
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(toolArgs, Map.class);
            Object result = targetTool.callWithMap(args);
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            log.error("Error executing tool {}", toolName, e);
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }
    
    /**
     * 将 BaseTool 列表转换为 OpenAI tools 格式
     */
    private ArrayNode formatToolsForApi(List<BaseTool> tools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        
        for (BaseTool tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");
            
            ObjectNode functionNode = objectMapper.createObjectNode();
            functionNode.put("name", tool.getName());
            functionNode.put("description", tool.getDescription());
            
            ObjectNode parametersNode = objectMapper.createObjectNode();
            parametersNode.put("type", "object");
            
            ObjectNode propertiesNode = objectMapper.createObjectNode();
            ArrayNode requiredArray = objectMapper.createArrayNode();
            
            Map<String, Map<String, Object>> inputs = tool.getInputs();
            if (inputs != null) {
                for (Map.Entry<String, Map<String, Object>> entry : inputs.entrySet()) {
                    String paramName = entry.getKey();
                    Map<String, Object> paramDef = entry.getValue();
                    
                    ObjectNode paramNode = objectMapper.createObjectNode();
                    if (paramDef != null) {
                        paramNode.put("type", (String) paramDef.getOrDefault("type", "string"));
                        if (paramDef.containsKey("description")) {
                            paramNode.put("description", (String) paramDef.get("description"));
                        }
                        
                        Boolean nullable = (Boolean) paramDef.getOrDefault("nullable", false);
                        if (!nullable) {
                            requiredArray.add(paramName);
                        }
                    } else {
                        paramNode.put("type", "string");
                        requiredArray.add(paramName);
                    }
                    
                    propertiesNode.set(paramName, paramNode);
                }
            }
            
            parametersNode.set("properties", propertiesNode);
            parametersNode.set("required", requiredArray);
            functionNode.set("parameters", parametersNode);
            
            toolNode.set("function", functionNode);
            toolsArray.add(toolNode);
        }
        
        // 添加 final_answer 工具
        toolsArray.add(createFinalAnswerTool());
        
        // 添加 think 工具
        toolsArray.add(createThinkTool());
        
        return toolsArray;
    }
    
    private ObjectNode createFinalAnswerTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", "final_answer");
        function.put("description", "Provide the final answer to the problem. Use this when you have completed the task.");
        
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode answerProp = objectMapper.createObjectNode();
        answerProp.put("type", "string");
        answerProp.put("description", "The final answer to return to the user");
        properties.set("answer", answerProp);
        
        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("answer");
        parameters.set("required", required);
        
        function.set("parameters", parameters);
        tool.set("function", function);
        
        return tool;
    }
    
    private ObjectNode createThinkTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", "think");
        function.put("description", "Record your thoughts for reflection.");
        
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode thoughtProp = objectMapper.createObjectNode();
        thoughtProp.put("type", "string");
        thoughtProp.put("description", "Your thought or reasoning");
        properties.set("thought", thoughtProp);
        
        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("thought");
        parameters.set("required", required);
        
        function.set("parameters", parameters);
        tool.set("function", function);
        
        return tool;
    }
    
    private String extractFinalAnswer(String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            return args.has("answer") ? args.get("answer").asText() : arguments;
        } catch (Exception e) {
            return arguments;
        }
    }
    
    private String extractThought(String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            return args.has("thought") ? args.get("thought").asText() : arguments;
        } catch (Exception e) {
            return arguments;
        }
    }
    
    // ==================== 结果类 ====================
    
    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ExecuteResult {
        private boolean success;
        private String content;
        private String error;
        private List<ToolCallInfo> toolCalls;
        private String toolResults;
        private String finalAnswer;
        private boolean isFinalAnswer;
        
        public static ExecuteResult success(String content) {
            return ExecuteResult.builder()
                    .success(true)
                    .content(content)
                    .build();
        }
        
        public static ExecuteResult error(String error) {
            return ExecuteResult.builder()
                    .success(false)
                    .error(error)
                    .build();
        }
        
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
        
        /**
         * 便捷方法：检查是否是最终答案
         * Lombok 对 isFinalAnswer 字段会生成 isIsFinalAnswer()，这里提供一个更友好的方法名
         */
        public boolean isFinalAnswer() {
            return isFinalAnswer;
        }
    }
    
    @Data
    public static class ToolCallInfo {
        private String id;
        private String name;
        private String arguments;
        private String result;
        private boolean isFinalAnswer;
    }
}

