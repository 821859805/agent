package com.chatbi.agent.types;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent状态管理类
 * 
 * 管理Agent执行过程中的所有状态信息：
 * - 输入和历史记录
 * - 任务信息
 * - 执行状态
 * - 元数据
 */
@Data
@NoArgsConstructor
public class AgentState {
    // ==================== 核心状态字段 ====================
    /**
     * 关联的Agent实例
     */
    private transient Object agent;
    
    /**
     * 当前输入
     */
    private Input input;
    
    /**
     * 对话历史
     */
    private History history = new History();
    
    /**
     * 当前任务描述
     */
    private String task;
    
    // ==================== 执行状态字段 ====================
    /**
     * 当前步数
     */
    private Integer stepCount = 0;
    
    /**
     * 是否是最终答案
     */
    private Boolean isFinalAnswer = false;
    
    /**
     * 最终答案值
     */
    private Object finalAnswerValue;
    
    /**
     * 是否已终止
     */
    private Boolean terminated = false;
    
    // ==================== 代码执行相关（CodeAgent专用）====================
    /**
     * 错误计数
     */
    private Integer errorCount = 0;
    
    /**
     * 反思计数
     */
    private Integer reflectionCount = 0;
    
    /**
     * 最后反思步数
     */
    private Integer lastReflectionStep = 0;
    
    /**
     * 最后置信度
     */
    private Double lastConfidence = 1.0;
    
    /**
     * 代码执行结果存储
     */
    private Map<Integer, CodeResult> codeResults = new HashMap<>();
    
    // ==================== 元数据 ====================
    /**
     * 附加元数据
     */
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt = LocalDateTime.now();
    
    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    // ==================== 内部类 ====================
    
    /**
     * 代码执行结果
     */
    @Data
    @NoArgsConstructor
    public static class CodeResult {
        private Object output;
        private String logs;
        private Boolean isFinalAnswer;
        private LocalDateTime executedAt = LocalDateTime.now();
        
        public CodeResult(Object output, String logs, Boolean isFinalAnswer) {
            this.output = output;
            this.logs = logs;
            this.isFinalAnswer = isFinalAnswer;
        }
    }
    
    // ==================== 构造函数 ====================
    
    public AgentState(Object agent) {
        this.agent = agent;
    }
    
    public AgentState(Input input) {
        this.input = input;
        if (input != null) {
            this.task = input.getQueryString();
        }
    }
    
    // ==================== 状态管理方法 ====================
    
    /**
     * 重置状态
     */
    public void reset() {
        this.input = null;
        this.history = new History();
        this.task = null;
        this.stepCount = 0;
        this.isFinalAnswer = false;
        this.finalAnswerValue = null;
        this.terminated = false;
        this.errorCount = 0;
        this.reflectionCount = 0;
        this.lastReflectionStep = 0;
        this.lastConfidence = 1.0;
        this.codeResults.clear();
        this.metadata.clear();
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 添加代码执行结果
     */
    public void addCodeResult(int blockIndex, Object output, String logs, Boolean isFinalAnswer) {
        codeResults.put(blockIndex, new CodeResult(output, logs, isFinalAnswer));
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 获取代码执行结果
     */
    public CodeResult getCodeResult(int blockIndex) {
        return codeResults.get(blockIndex);
    }
    
    /**
     * 增加步数
     */
    public void incrementStepCount() {
        this.stepCount++;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 增加错误计数
     */
    public void incrementErrorCount() {
        this.errorCount++;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 标记为最终答案
     */
    public void markAsFinalAnswer(Object answer) {
        this.isFinalAnswer = true;
        this.finalAnswerValue = answer;
        this.terminated = true;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 更新元数据
     */
    public void updateMetadata(String key, Object value) {
        this.metadata.put(key, value);
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 创建副本
     */
    public AgentState copy() {
        AgentState copy = new AgentState();
        copy.agent = this.agent;
        copy.input = this.input; // 浅拷贝
        copy.history = this.history.copy();
        copy.task = this.task;
        copy.stepCount = this.stepCount;
        copy.isFinalAnswer = this.isFinalAnswer;
        copy.finalAnswerValue = this.finalAnswerValue;
        copy.terminated = this.terminated;
        copy.errorCount = this.errorCount;
        copy.reflectionCount = this.reflectionCount;
        copy.lastReflectionStep = this.lastReflectionStep;
        copy.lastConfidence = this.lastConfidence;
        copy.codeResults = new HashMap<>(this.codeResults);
        copy.metadata = new HashMap<>(this.metadata);
        copy.createdAt = this.createdAt;
        copy.updatedAt = LocalDateTime.now();
        return copy;
    }
    
    @Override
    public String toString() {
        return String.format(
            "AgentState[task=%s, steps=%d, finalAnswer=%s, errors=%d]",
            task != null ? (task.length() > 50 ? task.substring(0, 50) + "..." : task) : "null",
            stepCount,
            isFinalAnswer,
            errorCount
        );
    }
}

