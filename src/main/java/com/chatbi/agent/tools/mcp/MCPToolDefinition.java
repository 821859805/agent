package com.chatbi.agent.tools.mcp;

import lombok.Data;
import java.util.Map;

/**
 * MCP 工具定义
 */
@Data
public class MCPToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;
}
