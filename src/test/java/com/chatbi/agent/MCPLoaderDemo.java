package com.chatbi.agent;

import com.chatbi.agent.tools.Tool;
import com.chatbi.agent.tools.mcp.MCPLoader;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.List;

/**
 * MCP 加载器演示
 */
@Slf4j
public class MCPLoaderDemo {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("Running MCP Loader Demo");
        System.out.println("=".repeat(80));
        System.out.println("\nNote: This demo requires MCP servers configured in mcp.json");
        System.out.println("The demo will pass even if MCP is not configured.\n");

        try {
            // 加载 MCP 工具
            String configPath = args.length > 0 ? args[0] : "mcp.json";
            List<Tool> tools = MCPLoader.loadMCPTools(Paths.get(configPath));

            System.out.println("Loaded " + tools.size() + " MCP tools");

            // 显示加载的工具
            if (!tools.isEmpty()) {
                System.out.println("\nAvailable tools:");
                for (Tool tool : tools) {
                    String desc = tool.getDescription();
                    if (desc.length() > 60) {
                        desc = desc.substring(0, 60) + "...";
                    }
                    System.out.println("  - " + tool.getName() + ": " + desc);
                }
            } else {
                System.out.println("\nNo MCP tools loaded. This may be normal if:");
                System.out.println("  1. mcp.json file doesn't exist");
                System.out.println("  2. No MCP servers are configured");
                System.out.println("  3. All servers are disabled");
                System.out.println("  4. Required dependencies (Node.js, etc.) are not installed");
            }

            System.out.println("\n" + "=".repeat(80));
            System.out.println("MCP Loader Demo completed! ✅");
            System.out.println("=".repeat(80));

        } catch (Exception e) {
            log.error("Error in MCP Loader Demo", e);
        } finally {
            // 清理连接
            MCPLoader.cleanupConnections();
        }
    }
}
