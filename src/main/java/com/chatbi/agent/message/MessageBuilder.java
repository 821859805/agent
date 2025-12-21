package com.chatbi.agent.message;

import com.chatbi.agent.tools.BaseTool;
import com.chatbi.agent.types.Input;
import dev.langchain4j.data.message.*;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MessageBuilder - 消息构建器
 * 
 * 类似于 Python minion 中的 construct_messages_from_template 函数，
 * 将 Input、工具、历史记录等信息构建为 OpenAI 格式的消息列表。
 * 
 * 支持：
 * - 模板化的系统提示词
 * - 工具描述的自动注入
 * - 对话历史的管理
 * - 多轮迭代的错误反馈
 */
@Slf4j
public class MessageBuilder {
    
    // ==================== 提示词模板 ====================
    
    /**
     * CodeMinion 系统提示词模板
     * 对应 minion 中 CODE_AGENT_SYSTEM_PROMPT
     */
    public static final String CODE_AGENT_SYSTEM_PROMPT = """
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
        
        ## Guidelines
        - Always explain your thinking before writing code
        - Use clear variable names
        - Handle errors gracefully
        - When the task is complete, always call final_answer() with your result
        
        ## Available Tools
        You can call tools as Python functions. For example:
        {{tools_description}}
        
        Let's start! Remember to end your code blocks with <end_code>.
        """;
    
    /**
     * Worker 提示词模板
     * 对应 minion 中的 WORKER_PROMPT + ASK_PROMPT_JINJA
     */
    public static final String WORKER_PROMPT_TEMPLATE = """
        Current Problem:
        {{#if context}}
        context:
        {{context}}
        {{/if}}
        {{#if instruction}}
        instruction:
        {{instruction}}
        {{/if}}
        
        query:
        {{query}}
        """;
    
    /**
     * Task 输入模板
     * 对应 minion 中的 TASK_INPUT
     */
    public static final String TASK_INPUT_TEMPLATE = """
        Current Task Input:
        instruction:
        {{task_instruction}}
        task type:
        {{task_type}}
        task description:
        {{task_description}}
        hint:
        {{task_hint}}
        """;
    
    // ==================== 构建方法 ====================
    
    /**
     * 构建 CodeMinion 消息列表
     * 对应 minion 的 construct_messages_with_history
     * 
     * @param query 用户查询
     * @param tools 可用工具列表
     * @param task 任务信息（可选）
     * @param error 上一次的错误信息
     * @param currentTurnAttempts 当前轮次的尝试历史
     * @param previousHistory 之前对话的历史
     * @return 格式化的消息列表
     */
    public static List<ChatMessage> buildCodeMinionMessages(
            String query,
            List<BaseTool> tools,
            Map<String, Object> task,
            String error,
            List<ConversationMessage> currentTurnAttempts,
            List<ConversationMessage> previousHistory) {
        
        List<ChatMessage> messages = new ArrayList<>();
        
        // 1. 构建系统消息
        String systemPrompt = buildSystemPrompt(tools);
        messages.add(SystemMessage.from(systemPrompt));
        
        // 2. 添加之前对话的历史（如果有）
        if (previousHistory != null && !previousHistory.isEmpty()) {
            for (ConversationMessage msg : previousHistory) {
                messages.add(convertToLangChainMessage(msg));
            }
        }
        
        // 3. 构建用户消息（包含查询、任务、错误反馈）
        List<Map<String, Object>> userContentParts = new ArrayList<>();
        
        // 添加查询
        String queryText = buildQueryContent(query, task);
        userContentParts.add(Map.of("type", "text", "text", queryText));
        
        // 添加错误反馈（如果有）
        if (error != null && !error.isEmpty()) {
            String errorText = String.format("""
                
                **Previous Error:**
                %s
                
                Please fix the error and try again.
                """, error);
            userContentParts.add(Map.of("type", "text", "text", errorText));
        }
        
        // 添加当前轮次的尝试历史
        if (currentTurnAttempts != null && !currentTurnAttempts.isEmpty()) {
            StringBuilder historyText = new StringBuilder("\n\n**Previous attempts:**\n");
            for (ConversationMessage msg : currentTurnAttempts) {
                historyText.append(String.format("%s: %s\n", msg.getRole(), msg.getContent()));
            }
            userContentParts.add(Map.of("type", "text", "text", historyText.toString()));
        }
        
        // 添加最终指令
        userContentParts.add(Map.of("type", "text", "text", 
                "\n\nLet's start! Remember to end your code blocks with <end_code>."));
        
        // 合并为单个用户消息
        StringBuilder combinedContent = new StringBuilder();
        for (Map<String, Object> part : userContentParts) {
            combinedContent.append(part.get("text"));
        }
        messages.add(UserMessage.from(combinedContent.toString()));
        
        return messages;
    }
    
    /**
     * 构建简单消息列表
     * 对应 minion 的 construct_simple_message
     */
    public static List<ChatMessage> buildSimpleMessages(Input input, List<BaseTool> tools) {
        List<ChatMessage> messages = new ArrayList<>();
        
        // 添加系统消息
        String systemPrompt = input.getSystemPrompt();
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            systemPrompt = buildSystemPrompt(tools);
        }
        messages.add(SystemMessage.from(systemPrompt));
        
        // 添加用户消息
        String userContent = buildUserContent(input);
        messages.add(UserMessage.from(userContent));
        
        return messages;
    }
    
    /**
     * 构建 Worker 消息列表
     * 对应 minion 的 WORKER_PROMPT 模板
     */
    public static List<ChatMessage> buildWorkerMessages(
            Input input, 
            List<BaseTool> tools,
            Map<String, Object> task) {
        
        List<ChatMessage> messages = new ArrayList<>();
        
        // 系统消息
        if (input.getSystemPrompt() != null && !input.getSystemPrompt().isEmpty()) {
            messages.add(SystemMessage.from(input.getSystemPrompt()));
        }
        
        // 用户消息 - 使用 WORKER_PROMPT 模板
        String userContent = renderTemplate(WORKER_PROMPT_TEMPLATE, Map.of(
            "context", nullToEmpty(input.getLongContext()),
            "instruction", nullToEmpty(input.getInstruction()),
            "query", input.getQueryString()
        ));
        
        // 如果有任务信息，添加任务模板
        if (task != null && !task.isEmpty()) {
            String taskContent = renderTemplate(TASK_INPUT_TEMPLATE, Map.of(
                "task_instruction", nullToEmpty(task.get("instruction")),
                "task_type", nullToEmpty(task.get("task_type")),
                "task_description", nullToEmpty(task.get("task_description")),
                "task_hint", nullToEmpty(task.get("hint"))
            ));
            userContent += "\n\n" + taskContent;
        }
        
        messages.add(UserMessage.from(userContent));
        
        return messages;
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 构建系统提示词
     */
    private static String buildSystemPrompt(List<BaseTool> tools) {
        String toolsDescription = buildToolsDescription(tools);
        return CODE_AGENT_SYSTEM_PROMPT.replace("{{tools_description}}", toolsDescription);
    }
    
    /**
     * 构建工具描述
     */
    public static String buildToolsDescription(List<BaseTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "- final_answer(answer): Provide the final answer to the problem\n" +
                   "- think(thought): Record your thoughts for reflection";
        }
        
        StringBuilder sb = new StringBuilder();
        for (BaseTool tool : tools) {
            sb.append(String.format("- %s(%s): %s\n", 
                    tool.getName(),
                    buildToolParams(tool),
                    tool.getDescription()));
        }
        sb.append("- final_answer(answer): Provide the final answer to the problem\n");
        sb.append("- think(thought): Record your thoughts for reflection");
        
        return sb.toString();
    }
    
    /**
     * 构建工具参数描述
     */
    private static String buildToolParams(BaseTool tool) {
        Map<String, Map<String, Object>> inputs = tool.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return "";
        }
        
        List<String> params = new ArrayList<>();
        for (String key : inputs.keySet()) {
            params.add(key);
        }
        return String.join(", ", params);
    }
    
    /**
     * 构建查询内容
     */
    private static String buildQueryContent(String query, Map<String, Object> task) {
        StringBuilder content = new StringBuilder();
        
        if (task != null && !task.isEmpty()) {
            // 任务模式
            String instruction = (String) task.getOrDefault("instruction", "");
            String description = (String) task.getOrDefault("task_description", "");
            
            if (!instruction.isEmpty()) {
                content.append("Task: ").append(instruction).append("\n\n");
            }
            if (!description.isEmpty()) {
                content.append("Description: ").append(description).append("\n\n");
            }
        }
        
        content.append("Query: ").append(query);
        
        return content.toString();
    }
    
    /**
     * 构建用户消息内容
     */
    private static String buildUserContent(Input input) {
        StringBuilder content = new StringBuilder();
        
        if (input.getLongContext() != null && !input.getLongContext().isEmpty()) {
            content.append("Context:\n").append(input.getLongContext()).append("\n\n");
        }
        
        if (input.getInstruction() != null && !input.getInstruction().isEmpty()) {
            content.append("Instruction: ").append(input.getInstruction()).append("\n\n");
        }
        
        content.append("Query: ").append(input.getQueryString());
        
        return content.toString();
    }
    
    /**
     * 简单模板渲染（替换 {{variable}}）
     */
    private static String renderTemplate(String template, Map<String, String> variables) {
        String result = template;
        
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            // 处理条件渲染 {{#if key}}...{{/if}}
            String ifPattern = "\\{\\{#if " + key + "\\}\\}([\\s\\S]*?)\\{\\{/if\\}\\}";
            if (value == null || value.isEmpty()) {
                result = result.replaceAll(ifPattern, "");
            } else {
                result = result.replaceAll(ifPattern, "$1");
            }
            
            // 替换变量
            result = result.replace("{{" + key + "}}", nullToEmpty(value));
        }
        
        return result;
    }
    
    /**
     * 将 ConversationMessage 转换为 LangChain4j 消息
     */
    private static ChatMessage convertToLangChainMessage(ConversationMessage msg) {
        return switch (msg.getRole().toLowerCase()) {
            case "system" -> SystemMessage.from(msg.getContent());
            case "assistant" -> AiMessage.from(msg.getContent());
            case "tool" -> ToolExecutionResultMessage.from(
                    null, // toolExecutionRequest
                    msg.getToolName() != null ? msg.getToolName() : "unknown",
                    msg.getContent()
            );
            default -> UserMessage.from(msg.getContent());
        };
    }
    
    private static String nullToEmpty(Object obj) {
        return obj == null ? "" : obj.toString();
    }
    
    // ==================== 数据类 ====================
    
    /**
     * 对话消息（类似 OpenAI 的 message 格式）
     */
    @Data
    @Builder
    public static class ConversationMessage {
        private String role; // "system", "user", "assistant", "tool"
        private String content;
        private String toolName; // for tool messages
        private String toolCallId; // for tool messages
        private List<ToolCall> toolCalls; // for assistant messages with tool calls
    }
    
    /**
     * 工具调用
     */
    @Data
    @Builder
    public static class ToolCall {
        private String id;
        private String name;
        private String arguments;
    }
}

