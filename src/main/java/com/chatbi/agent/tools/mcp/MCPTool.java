package com.chatbi.agent.tools.mcp;

import com.chatbi.agent.tools.Tool;
import com.chatbi.agent.tools.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * MCP 工具的包装类
 */
@Slf4j
public class MCPTool extends Tool {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final MCPServerConnection connection;

    public MCPTool(String name, String description, Map<String, Object> parameters, 
                   MCPServerConnection connection) {
        this.name = name;
        this.description = description != null ? description : "";
        this.parameters = parameters != null ? parameters : Map.of();
        this.connection = connection;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            if (connection == null || !connection.isConnected()) {
                return createErrorResult("MCP server connection is not available");
            }

            // 调用 MCP 工具
            MCPResponse response = connection.callTool(name, args);
            
            if (response == null) {
                return createErrorResult("No response from MCP server");
            }

            // 处理响应内容
            StringBuilder contentBuilder = new StringBuilder();
            if (response.getContent() != null) {
                for (Object item : response.getContent()) {
                    if (item instanceof Map) {
                        Map<String, Object> contentItem = (Map<String, Object>) item;
                        if (contentItem.containsKey("text")) {
                            contentBuilder.append(contentItem.get("text"));
                        } else {
                            contentBuilder.append(item.toString());
                        }
                    } else if (item instanceof String) {
                        contentBuilder.append(item);
                    } else {
                        contentBuilder.append(item.toString());
                    }
                    contentBuilder.append("\n");
                }
            }

            String content = contentBuilder.toString().trim();
            boolean isError = response.isError() != null && response.isError();

            ToolResult result = new ToolResult();
            result.setSuccess(!isError);
            result.setContent(content);
            result.setError(isError ? "Tool returned error" : null);
            
            return result;

        } catch (Exception e) {
            log.error("MCP tool execution failed: {}", name, e);
            return createErrorResult("MCP tool execution failed: " + e.getMessage());
        }
    }

    private ToolResult createErrorResult(String errorMessage) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setContent("");
        result.setError(errorMessage);
        return result;
    }
}
