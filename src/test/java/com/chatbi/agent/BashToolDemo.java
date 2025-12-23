package com.chatbi.agent;

import com.chatbi.agent.tools.ToolResult;
import com.chatbi.agent.tools.bash.BashOutputResult;
import com.chatbi.agent.tools.bash.BashTool;

import java.util.Map;

/**
 * BashTool 使用示例
 * 演示如何执行 bash 命令
 */
public class BashToolDemo {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Basic Tools Usage Examples");
        System.out.println("=".repeat(60));
        System.out.println("\n这些示例展示了如何直接使用核心工具。");
        System.out.println("在真实的 agent 场景中，LLM 会决定使用哪些工具。\n");

        demoBashTool();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("All demos completed! ✅");
        System.out.println("=".repeat(60));
    }

    /**
     * 演示：执行 bash 命令
     */
    public static void demoBashTool() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Demo 4: BashTool - Execute bash commands");
        System.out.println("=".repeat(60));

        BashTool tool = new BashTool();
        String osName = System.getProperty("os.name");
        boolean isWindows = osName != null && osName.toLowerCase().contains("windows");

        // Example 1: List files
        if (isWindows) {
            System.out.println("\nCommand: dir");
            ToolResult result = tool.execute(Map.of("command", "dir"));
            printResult(result, "Command executed successfully");
        } else {
            System.out.println("\nCommand: ls -la");
            ToolResult result = tool.execute(Map.of("command", "ls -la"));
            printResult(result, "Command executed successfully", true);
        }

        // Example 2: Get current directory
        if (isWindows) {
            System.out.println("\nCommand: pwd (PowerShell: Get-Location)");
            ToolResult result = tool.execute(Map.of("command", "Get-Location"));
            if (result.isSuccess()) {
                System.out.println("✅ Current directory: " + result.getContent().trim());
            } else {
                System.out.println("❌ Failed: " + result.getError());
            }
        } else {
            System.out.println("\nCommand: pwd");
            ToolResult result = tool.execute(Map.of("command", "pwd"));
            if (result.isSuccess()) {
                System.out.println("✅ Current directory: " + result.getContent().trim());
            } else {
                System.out.println("❌ Failed: " + result.getError());
            }
        }

        // Example 3: Echo
        if (isWindows) {
            System.out.println("\nCommand: echo 'Hello from BashTool!' (PowerShell: Write-Output)");
            ToolResult result = tool.execute(Map.of("command", "Write-Output 'Hello from BashTool!'"));
            if (result.isSuccess()) {
                System.out.println("✅ Output: " + result.getContent().trim());
            } else {
                System.out.println("❌ Failed: " + result.getError());
            }
        } else {
            System.out.println("\nCommand: echo 'Hello from BashTool!'");
            ToolResult result = tool.execute(Map.of("command", "echo 'Hello from BashTool!'"));
            if (result.isSuccess()) {
                System.out.println("✅ Output: " + result.getContent().trim());
            } else {
                System.out.println("❌ Failed: " + result.getError());
            }
        }
    }

    /**
     * 打印结果
     */
    private static void printResult(ToolResult result, String successMessage) {
        printResult(result, successMessage, false);
    }

    /**
     * 打印结果
     */
    private static void printResult(ToolResult result, String successMessage, boolean truncate) {
        if (result.isSuccess()) {
            System.out.println("✅ " + successMessage);
            String content = result.getContent();
            if (truncate && content.length() > 200) {
                System.out.println("Output:\n" + content.substring(0, 200) + "...");
            } else {
                System.out.println("Output:\n" + content);
            }
            
            // 如果是 BashOutputResult，显示详细信息
            if (result instanceof BashOutputResult) {
                BashOutputResult bashResult = (BashOutputResult) result;
                if (bashResult.getExitCode() != 0) {
                    System.out.println("Exit code: " + bashResult.getExitCode());
                }
                if (bashResult.getStderr() != null && !bashResult.getStderr().isEmpty()) {
                    System.out.println("Stderr: " + bashResult.getStderr());
                }
            }
        } else {
            System.out.println("❌ Failed: " + result.getError());
        }
    }
}

