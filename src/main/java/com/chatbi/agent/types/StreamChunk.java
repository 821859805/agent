package com.chatbi.agent.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * StreamChunk - 流式输出块
 * 
 * 类似于 Python minion 中的 StreamChunk，用于封装流式输出的内容。
 * 支持不同类型的输出块：
 * - step_start: 步骤开始
 * - llm_output: LLM 输出
 * - tool_call: 工具调用
 * - tool_response: 工具响应
 * - code_execution: 代码执行
 * - observation: 执行结果观察
 * - error: 错误信息
 * - final_answer: 最终答案
 * - step_end: 步骤结束
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamChunk {
    
    /**
     * 块的唯一标识符
     */
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    
    /**
     * 块的内容
     */
    private String content;
    
    /**
     * 块的类型
     */
    @Builder.Default
    private ChunkType chunkType = ChunkType.LLM_OUTPUT;
    
    /**
     * 元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 时间戳
     */
    @Builder.Default
    private long timestamp = Instant.now().toEpochMilli();
    
    /**
     * 当前迭代/步骤编号
     */
    private Integer iteration;
    
    /**
     * 最大迭代数
     */
    private Integer maxIterations;
    
    /**
     * 工具名称（用于 tool_call 类型）
     */
    private String toolName;
    
    /**
     * 工具参数（用于 tool_call 类型）
     */
    private Map<String, Object> toolArgs;
    
    /**
     * 是否是最终块
     */
    @Builder.Default
    private boolean isFinal = false;
    
    /**
     * 是否成功（用于 tool_response, code_execution 等）
     */
    private Boolean success;
    
    /**
     * 错误信息（如果有）
     */
    private String error;
    
    /**
     * 块类型枚举
     */
    public enum ChunkType {
        /**
         * 步骤开始
         */
        STEP_START,
        
        /**
         * LLM 输出（思考过程）
         */
        LLM_OUTPUT,
        
        /**
         * Thought 阶段
         */
        THOUGHT,
        
        /**
         * 工具调用
         */
        TOOL_CALL,
        
        /**
         * 工具响应
         */
        TOOL_RESPONSE,
        
        /**
         * 代码执行
         */
        CODE_EXECUTION,
        
        /**
         * 代码执行结果观察
         */
        OBSERVATION,
        
        /**
         * 错误信息
         */
        ERROR,
        
        /**
         * 最终答案
         */
        FINAL_ANSWER,
        
        /**
         * 步骤结束
         */
        STEP_END,
        
        /**
         * Skill 加载
         */
        SKILL_LOADING,
        
        /**
         * 文本输出
         */
        TEXT
    }
    
    // ==================== 静态工厂方法 ====================
    
    /**
     * 创建步骤开始块
     */
    public static StreamChunk stepStart(int iteration, int maxIterations) {
        return StreamChunk.builder()
                .content(String.format("Step %d/%d", iteration + 1, maxIterations))
                .chunkType(ChunkType.STEP_START)
                .iteration(iteration)
                .maxIterations(maxIterations)
                .build();
    }
    
    /**
     * 创建 LLM 输出块
     */
    public static StreamChunk llmOutput(String content) {
        return StreamChunk.builder()
                .content(content)
                .chunkType(ChunkType.LLM_OUTPUT)
                .build();
    }
    
    /**
     * 创建 Thought 块
     */
    public static StreamChunk thought(String content) {
        return StreamChunk.builder()
                .content(content)
                .chunkType(ChunkType.THOUGHT)
                .build();
    }
    
    /**
     * 创建工具调用块
     */
    public static StreamChunk toolCall(String toolName, Map<String, Object> args) {
        return StreamChunk.builder()
                .content(String.format("Calling tool: %s", toolName))
                .chunkType(ChunkType.TOOL_CALL)
                .toolName(toolName)
                .toolArgs(args)
                .build();
    }
    
    /**
     * 创建工具调用块（使用 JSON 字符串参数）
     */
    public static StreamChunk toolCall(String toolName, String argsJson) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("argsJson", argsJson);
        
        return StreamChunk.builder()
                .content(String.format("Calling tool: %s with args: %s", toolName, argsJson))
                .chunkType(ChunkType.TOOL_CALL)
                .toolName(toolName)
                .metadata(metadata)
                .build();
    }
    
    /**
     * 创建工具响应块
     */
    public static StreamChunk toolResponse(String toolName, String result, boolean success) {
        return StreamChunk.builder()
                .content(result)
                .chunkType(ChunkType.TOOL_RESPONSE)
                .toolName(toolName)
                .success(success)
                .build();
    }
    
    /**
     * 创建代码执行块
     */
    public static StreamChunk codeExecution(String code) {
        return StreamChunk.builder()
                .content(code)
                .chunkType(ChunkType.CODE_EXECUTION)
                .build();
    }
    
    /**
     * 创建观察块
     */
    public static StreamChunk observation(String result, boolean success) {
        return StreamChunk.builder()
                .content(result)
                .chunkType(ChunkType.OBSERVATION)
                .success(success)
                .build();
    }
    
    /**
     * 创建错误块
     */
    public static StreamChunk error(String error) {
        return StreamChunk.builder()
                .content(error)
                .chunkType(ChunkType.ERROR)
                .error(error)
                .success(false)
                .build();
    }
    
    /**
     * 创建最终答案块
     */
    public static StreamChunk finalAnswer(String answer) {
        return StreamChunk.builder()
                .content(answer)
                .chunkType(ChunkType.FINAL_ANSWER)
                .isFinal(true)
                .build();
    }
    
    /**
     * 创建步骤结束块
     */
    public static StreamChunk stepEnd(int iteration, long duration) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("duration_ms", duration);
        
        return StreamChunk.builder()
                .content(String.format("Step %d completed in %.2fs", iteration + 1, duration / 1000.0))
                .chunkType(ChunkType.STEP_END)
                .iteration(iteration)
                .metadata(meta)
                .build();
    }
    
    /**
     * 创建 Skill 加载块
     */
    public static StreamChunk skillLoading(String skillName) {
        return StreamChunk.builder()
                .content(String.format("Loading skill: %s", skillName))
                .chunkType(ChunkType.SKILL_LOADING)
                .toolName(skillName)
                .build();
    }
    
    /**
     * 添加元数据
     */
    public StreamChunk withMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
        return this;
    }
    
    /**
     * 转换为 SSE 格式
     */
    public String toSSE() {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(chunkType.name().toLowerCase()).append("\n");
        sb.append("id: ").append(id).append("\n");
        sb.append("data: ").append(toJson()).append("\n\n");
        return sb.toString();
    }
    
    /**
     * 转换为 JSON（简化版）
     */
    public String toJson() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            return String.format("{\"content\":\"%s\",\"type\":\"%s\"}", 
                content != null ? content.replace("\"", "\\\"") : "", 
                chunkType.name());
        }
    }
}

