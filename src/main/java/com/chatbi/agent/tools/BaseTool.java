package com.chatbi.agent.tools;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具基类
 * 
 * 定义所有工具的基本接口，支持：
 * - 同步和异步执行
 * - 工具描述和参数定义
 * - 状态感知（可选）
 */
@Slf4j
@Data
public abstract class BaseTool {
    /**
     * 工具名称
     */
    protected String name = "base_tool";
    
    /**
     * 工具描述
     */
    protected String description = "基础工具类，所有工具应继承此类";
    
    /**
     * 输入参数定义
     * Map结构: {参数名: {type: 类型, description: 描述, required: 是否必需}}
     */
    protected Map<String, Map<String, Object>> inputs = new HashMap<>();
    
    /**
     * 输出类型
     */
    protected String outputType = "string";
    
    /**
     * 是否只读（不修改外部状态）
     */
    protected Boolean readonly = null;
    
    /**
     * 是否需要接收Agent状态
     */
    protected Boolean needsState = false;
    
    /**
     * 是否已初始化
     */
    protected Boolean isInitialized = false;
    
    // ==================== 执行方法 ====================
    
    /**
     * 同步调用工具
     */
    public Object call(Object... args) {
        if (!isInitialized) {
            setup();
        }
        return forward(args);
    }
    
    /**
     * 异步调用工具
     */
    public CompletableFuture<Object> callAsync(Object... args) {
        return CompletableFuture.supplyAsync(() -> call(args));
    }
    
    /**
     * 使用Map参数调用
     */
    public Object callWithMap(Map<String, Object> kwargs) {
        if (!isInitialized) {
            setup();
        }
        return forwardWithMap(kwargs);
    }
    
    /**
     * 实际的工具执行逻辑（位置参数）
     * 子类必须实现此方法
     */
    protected abstract Object forward(Object... args);
    
    /**
     * 使用Map参数的执行逻辑
     * 子类可以重写此方法
     */
    protected Object forwardWithMap(Map<String, Object> kwargs) {
        // 默认实现：将Map转换为位置参数
        return forward(kwargs.values().toArray());
    }
    
    // ==================== 生命周期方法 ====================
    
    /**
     * 初始化设置
     * 用于执行耗时的初始化操作
     */
    public void setup() {
        this.isInitialized = true;
        log.debug("Tool {} initialized", name);
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        log.debug("Tool {} cleanup", name);
    }
    
    // ==================== 格式化方法 ====================
    
    /**
     * 格式化输出用于LLM观察
     */
    public String formatForObservation(Object output) {
        return output != null ? output.toString() : "";
    }
    
    /**
     * 转换为字典表示（用于序列化）
     */
    public Map<String, Object> toDict() {
        Map<String, Object> dict = new HashMap<>();
        dict.put("name", name);
        dict.put("description", description);
        dict.put("inputs", inputs);
        dict.put("outputType", outputType);
        dict.put("readonly", readonly);
        return dict;
    }
    
    /**
     * 生成工具的函数调用格式（OpenAI风格）
     */
    public Map<String, Object> toFunctionSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("name", name);
        schema.put("description", description);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : inputs.entrySet()) {
            properties.put(entry.getKey(), entry.getValue());
        }
        parameters.put("properties", properties);
        
        schema.put("parameters", parameters);
        return schema;
    }
    
    @Override
    public String toString() {
        return String.format("Tool[%s: %s]", name, description);
    }
}

