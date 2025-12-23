package com.chatbi.agent.tools;

import java.util.Map;

/**
 * 所有工具的基类
 */
public abstract class Tool {
    
    /**
     * 工具名称
     */
    public abstract String getName();
    
    /**
     * 工具描述
     */
    public abstract String getDescription();
    
    /**
     * 工具参数模式（JSON Schema 格式）
     */
    public abstract Map<String, Object> getParameters();
    
    /**
     * 执行工具
     */
    public abstract ToolResult execute(Map<String, Object> args);
    
    /**
     * 转换为工具模式
     */
    public Map<String, Object> toSchema() {
        return Map.of(
            "name", getName(),
            "description", getDescription(),
            "input_schema", getParameters()
        );
    }
    
    /**
     * 转换为 OpenAI 工具模式
     */
    public Map<String, Object> toOpenAISchema() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", getName(),
                "description", getDescription(),
                "parameters", getParameters()
            )
        );
    }
}

