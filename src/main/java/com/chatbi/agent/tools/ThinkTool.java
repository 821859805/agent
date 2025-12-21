package com.chatbi.agent.tools;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 思考工具
 * 
 * 用于Agent进行自我反思和深度思考。
 * 这是一个内省工具，帮助Agent分析当前情况并做出更好的决策。
 */
@Slf4j
public class ThinkTool extends BaseTool {
    
    public ThinkTool() {
        this.name = "think";
        this.description = "Use this tool to think about the current situation, " +
                          "analyze progress, reflect on errors, and plan next steps. " +
                          "This is an introspective tool that helps organize thoughts " +
                          "and make better decisions.";
        this.outputType = "string";
        this.readonly = true;
        
        // 定义输入参数
        Map<String, Object> thoughtParam = new HashMap<>();
        thoughtParam.put("type", "string");
        thoughtParam.put("description", "The thought or reflection content");
        thoughtParam.put("required", true);
        
        this.inputs = new HashMap<>();
        this.inputs.put("thought", thoughtParam);
    }
    
    @Override
    protected Object forward(Object... args) {
        if (args == null || args.length == 0) {
            return "No thought provided.";
        }
        
        String thought = String.valueOf(args[0]);
        log.debug("Agent thinking: {}", thought);
        
        // 思考工具不改变状态，只是记录思考过程
        return String.format("Thought recorded: %s", thought);
    }
    
    @Override
    protected Object forwardWithMap(Map<String, Object> kwargs) {
        Object thought = kwargs.get("thought");
        if (thought == null) {
            return "No thought provided.";
        }
        return forward(thought);
    }
}

