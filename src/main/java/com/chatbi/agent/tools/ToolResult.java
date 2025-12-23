package com.chatbi.agent.tools;

import lombok.Data;

/**
 * 工具执行结果基类
 */
@Data
public class ToolResult {
    private boolean success;
    private String content = "";
    private String error;
}

