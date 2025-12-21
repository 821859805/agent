package com.chatbi.agent.message;

import com.chatbi.agent.tools.BaseTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolParameters;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * ToolFormatter - 工具格式化器
 * 
 * 类似于 Python minion 中的 _format_tools_for_api 方法，
 * 将 BaseTool 列表转换为 OpenAI 工具调用格式。
 * 
 * 这是实现 Function Calling 的关键组件。
 */
@Slf4j
public class ToolFormatter {
    
    /**
     * 将工具列表转换为 LangChain4j ToolSpecification 列表
     * 
     * 对应 minion 的 _format_tools_for_api 方法
     * 
     * @param tools BaseTool 列表
     * @return ToolSpecification 列表，用于 LLM 的 function calling
     */
    public static List<ToolSpecification> formatToolsForApi(List<BaseTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<ToolSpecification> specifications = new ArrayList<>();
        
        for (BaseTool tool : tools) {
            try {
                ToolSpecification spec = convertToToolSpecification(tool);
                if (spec != null) {
                    specifications.add(spec);
                }
            } catch (Exception e) {
                log.warn("Failed to convert tool {} to specification: {}", 
                        tool.getName(), e.getMessage());
            }
        }
        
        // 添加 final_answer 工具
        specifications.add(createFinalAnswerTool());
        
        // 添加 think 工具
        specifications.add(createThinkTool());
        
        return specifications;
    }
    
    /**
     * 将 BaseTool 转换为 ToolSpecification
     */
    private static ToolSpecification convertToToolSpecification(BaseTool tool) {
        String name = tool.getName();
        String description = tool.getDescription();
        Map<String, Map<String, Object>> inputs = tool.getInputs();
        
        // 构建参数定义
        ToolParameters.Builder paramsBuilder = ToolParameters.builder();
        
        if (inputs != null && !inputs.isEmpty()) {
            Map<String, Map<String, Object>> properties = new HashMap<>();
            List<String> required = new ArrayList<>();
            
            for (Map.Entry<String, Map<String, Object>> entry : inputs.entrySet()) {
                String paramName = entry.getKey();
                Map<String, Object> paramDef = entry.getValue();
                
                Map<String, Object> paramSchema = new HashMap<>();
                
                if (paramDef != null && !paramDef.isEmpty()) {
                    // 类型
                    String type = (String) paramDef.getOrDefault("type", "string");
                    paramSchema.put("type", type);
                    
                    // 描述
                    String paramDesc = (String) paramDef.get("description");
                    if (paramDesc != null) {
                        paramSchema.put("description", paramDesc);
                    }
                    
                    // 是否必需
                    Boolean nullable = (Boolean) paramDef.getOrDefault("nullable", false);
                    if (!nullable) {
                        required.add(paramName);
                    }
                    
                    // 枚举值
                    Object enumValues = paramDef.get("enum");
                    if (enumValues != null) {
                        paramSchema.put("enum", enumValues);
                    }
                } else {
                    // 默认为字符串类型
                    paramSchema.put("type", "string");
                    required.add(paramName);
                }
                
                properties.put(paramName, paramSchema);
            }
            
            paramsBuilder.properties(properties);
            paramsBuilder.required(required);
        }
        
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(paramsBuilder.build())
                .build();
    }
    
    /**
     * 创建 final_answer 工具
     * 
     * 对应 minion 中的 final_answer 工具
     */
    private static ToolSpecification createFinalAnswerTool() {
        return ToolSpecification.builder()
                .name("final_answer")
                .description("Provide the final answer to the problem. " +
                        "Use this when you have completed the task and want to return the result.")
                .parameters(ToolParameters.builder()
                        .properties(Map.of(
                                "answer", Map.of(
                                        "type", "string",
                                        "description", "The final answer to return to the user"
                                )
                        ))
                        .required(List.of("answer"))
                        .build())
                .build();
    }
    
    /**
     * 创建 think 工具
     * 
     * 用于记录思考过程
     */
    private static ToolSpecification createThinkTool() {
        return ToolSpecification.builder()
                .name("think")
                .description("Record your thoughts for reflection. " +
                        "Use this to organize your thinking process.")
                .parameters(ToolParameters.builder()
                        .properties(Map.of(
                                "thought", Map.of(
                                        "type", "string",
                                        "description", "Your thought or reasoning"
                                )
                        ))
                        .required(List.of("thought"))
                        .build())
                .build();
    }
    
    /**
     * 将工具列表转换为 JSON Schema 格式（OpenAI API 原始格式）
     * 
     * 用于直接传递给某些 LLM API
     */
    public static List<Map<String, Object>> formatToolsAsJsonSchema(List<BaseTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        if (tools != null) {
            for (BaseTool tool : tools) {
                Map<String, Object> toolDef = new HashMap<>();
                toolDef.put("type", "function");
                
                Map<String, Object> function = new HashMap<>();
                function.put("name", tool.getName());
                function.put("description", tool.getDescription());
                
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("type", "object");
                
                Map<String, Object> properties = new HashMap<>();
                List<String> required = new ArrayList<>();
                
                Map<String, Map<String, Object>> inputs = tool.getInputs();
                if (inputs != null) {
                    for (Map.Entry<String, Map<String, Object>> entry : inputs.entrySet()) {
                        String paramName = entry.getKey();
                        Map<String, Object> paramDef = entry.getValue();
                        
                        if (paramDef != null && !paramDef.isEmpty()) {
                            properties.put(paramName, paramDef);
                            
                            Boolean nullable = (Boolean) paramDef.getOrDefault("nullable", false);
                            if (!nullable) {
                                required.add(paramName);
                            }
                        } else {
                            properties.put(paramName, Map.of("type", "string"));
                            required.add(paramName);
                        }
                    }
                }
                
                parameters.put("properties", properties);
                parameters.put("required", required);
                function.put("parameters", parameters);
                
                toolDef.put("function", function);
                result.add(toolDef);
            }
        }
        
        // 添加 final_answer
        result.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "final_answer",
                        "description", "Provide the final answer to the problem",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "answer", Map.of(
                                                "type", "string",
                                                "description", "The final answer"
                                        )
                                ),
                                "required", List.of("answer")
                        )
                )
        ));
        
        return result;
    }
}

