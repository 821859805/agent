package com.chatbi.agent.tools.mcp;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * MCP 响应数据结构
 */
@Data
public class MCPResponse {
    private String jsonrpc = "2.0";
    private String id;
    private Object result;
    private MCPError error;
    private Boolean isError;
    private List<Object> content;

    @Data
    public static class MCPError {
        private Integer code;
        private String message;
        private Object data;
    }

    /**
     * 从 JSON-RPC 响应中提取内容
     */
    @SuppressWarnings("unchecked")
    public void extractContent() {
        if (result != null && result instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) result;
            
            // MCP 工具调用结果通常包含 content 字段
            if (resultMap.containsKey("content")) {
                Object contentObj = resultMap.get("content");
                if (contentObj instanceof List) {
                    this.content = (List<Object>) contentObj;
                }
            }
            
            // 检查是否有 isError 字段
            if (resultMap.containsKey("isError")) {
                this.isError = (Boolean) resultMap.get("isError");
            }
        }
    }
}
