package com.chatbi.agent.types;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent响应数据模型
 * 
 * 封装Agent执行步骤的返回结果，包含：
 * - 响应内容和答案
 * - 评分和置信度
 * - 执行状态标志
 * - 元数据和统计信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    // ==================== 核心响应字段 ====================
    /**
     * 原始响应内容（LLM的完整输出）
     */
    private String rawResponse;
    
    /**
     * 处理后的内容
     */
    private String content;
    
    /**
     * 提取的答案
     */
    private String answer;
    
    /**
     * 最终答案（如果已确定）
     */
    private Object finalAnswer;
    
    // ==================== 评分字段 ====================
    /**
     * 结果评分（0.0-1.0）
     */
    @Builder.Default
    private Double score = 1.0;
    
    /**
     * 置信度（0.0-1.0）
     */
    @Builder.Default
    private Double confidence = 1.0;
    
    // ==================== 状态标志 ====================
    /**
     * 是否成功
     */
    @Builder.Default
    private Boolean success = true;
    
    /**
     * 是否已终止（任务完成）
     */
    @Builder.Default
    private Boolean terminated = false;
    
    /**
     * 是否被截断
     */
    @Builder.Default
    private Boolean truncated = false;
    
    /**
     * 是否是最终答案
     */
    @Builder.Default
    private Boolean isFinalAnswer = false;
    
    // ==================== 错误处理 ====================
    /**
     * 错误信息
     */
    private String error;
    
    // ==================== 统计信息 ====================
    /**
     * 执行时间（毫秒）
     */
    private Long executionTime;
    
    /**
     * 使用的token数量
     */
    private Integer tokensUsed;
    
    /**
     * 时间戳
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    // ==================== 元数据 ====================
    /**
     * 附加信息
     */
    @Builder.Default
    private Map<String, Object> info = new HashMap<>();
    
    /**
     * 思考内容（DeepSeek思考模式）
     */
    private String thinkContent;
    
    // ==================== 工具调用相关 ====================
    
    /**
     * 工具调用列表（Function Calling）
     */
    @Builder.Default
    private List<ToolCallInfo> toolCalls = new ArrayList<>();
    
    /**
     * 工具执行结果
     */
    private String toolResults;
    
    // ==================== 便捷方法 ====================
    
    /**
     * 判断响应是否成功
     */
    public boolean isSuccess() {
        return success != null && success && !hasError();
    }
    
    /**
     * 判断响应是否完成
     */
    public boolean isDone() {
        return (terminated != null && terminated) || 
               (isFinalAnswer != null && isFinalAnswer);
    }
    
    /**
     * 判断是否有错误
     */
    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
    
    /**
     * 获取最佳答案（优先返回answer，其次返回content）
     */
    public String getBestAnswer() {
        if (answer != null && !answer.isEmpty()) {
            return answer;
        }
        if (content != null && !content.isEmpty()) {
            return content;
        }
        return rawResponse;
    }
    
    /**
     * 创建成功响应
     */
    public static AgentResponse success(String answer) {
        AgentResponse response = new AgentResponse();
        response.setAnswer(answer);
        response.setContent(answer);
        response.setTerminated(true);
        response.setIsFinalAnswer(true);
        return response;
    }
    
    /**
     * 创建错误响应
     */
    public static AgentResponse error(String errorMessage) {
        AgentResponse response = new AgentResponse();
        response.setError(errorMessage);
        response.setContent("Error: " + errorMessage);
        response.setTerminated(true);
        return response;
    }
    
    /**
     * 创建中间响应（任务未完成）
     */
    public static AgentResponse intermediate(String content) {
        AgentResponse response = new AgentResponse();
        response.setContent(content);
        response.setTerminated(false);
        response.setIsFinalAnswer(false);
        return response;
    }
    
    /**
     * 添加元信息
     */
    public AgentResponse withInfo(String key, Object value) {
        this.info.put(key, value);
        return this;
    }
    
    /**
     * 判断是否有工具调用
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 工具调用信息
     * 
     * 对应 OpenAI 的 tool_call 格式
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallInfo {
        /**
         * 调用ID
         */
        private String id;
        
        /**
         * 工具名称
         */
        private String name;
        
        /**
         * 参数（JSON字符串）
         */
        private String arguments;
        
        /**
         * 执行结果
         */
        private String result;
        
        /**
         * 是否执行成功
         */
        @Builder.Default
        private Boolean success = true;
    }
}

