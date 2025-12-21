package com.chatbi.agent.minion;

import com.chatbi.agent.main.Brain;
import com.chatbi.agent.tools.BaseTool;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.types.StreamChunk;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * WorkerMinion - 工作者 Minion 基类
 * 
 * 类似于 Python minion 中的 WorkerMinion，提供：
 * 1. 基础的执行框架
 * 2. 工具和 Brain 的访问
 * 3. 同步和异步执行接口
 * 4. 流式输出支持
 */
@Slf4j
@Data
public abstract class WorkerMinion {
    
    /**
     * 输入对象
     */
    protected Input input;
    
    /**
     * Brain 引用
     */
    protected Brain brain;
    
    /**
     * 任务描述
     */
    protected java.util.Map<String, Object> task;
    
    /**
     * 答案
     */
    protected String answer;
    
    /**
     * 思考内容
     */
    protected String thinkContent;
    
    /**
     * 原始答案
     */
    protected String rawAnswer;
    
    // ==================== 构造函数 ====================
    
    public WorkerMinion() {}
    
    public WorkerMinion(Input input, Brain brain) {
        this.input = input;
        this.brain = brain;
    }
    
    public WorkerMinion(Input input, Brain brain, java.util.Map<String, Object> task) {
        this.input = input;
        this.brain = brain;
        this.task = task;
    }
    
    // ==================== 抽象方法 ====================
    
    /**
     * 执行 Minion 任务
     */
    public abstract CompletableFuture<AgentResponse> execute();
    
    /**
     * 流式执行
     */
    public abstract void executeStream(Consumer<StreamChunk> chunkConsumer);
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取可用工具
     */
    @SuppressWarnings("unchecked")
    protected List<BaseTool> getTools() {
        List<BaseTool> tools = new java.util.ArrayList<>();
        
        // 从 input 获取工具
        if (input != null && input.getTools() != null) {
            for (Object tool : input.getTools()) {
                if (tool instanceof BaseTool) {
                    tools.add((BaseTool) tool);
                }
            }
        }
        
        // 从 brain 获取工具
        if (brain != null && brain.getTools() != null) {
            tools.addAll(brain.getTools());
        }
        
        return tools;
    }
    
    /**
     * 获取查询字符串
     */
    protected String getQuery() {
        if (task != null) {
            Object instruction = task.get("instruction");
            if (instruction != null && !instruction.toString().isEmpty()) {
                return instruction.toString();
            }
            Object taskDesc = task.get("task_description");
            if (taskDesc != null && !taskDesc.toString().isEmpty()) {
                return taskDesc.toString();
            }
        }
        return input != null ? input.getQueryString() : "";
    }
}

