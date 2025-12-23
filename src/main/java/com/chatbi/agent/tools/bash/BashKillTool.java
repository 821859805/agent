package com.chatbi.agent.tools.bash;

import com.chatbi.agent.tools.Tool;
import com.chatbi.agent.tools.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 终止一个正在运行的后台 shell
 */
@Slf4j
public class BashKillTool extends Tool {
    
    @Override
    public String getName() {
        return "bash_kill";
    }
    
    @Override
    public String getDescription() {
        return """
            Kills a running background bash shell by its ID.

            - Takes a bash_id parameter identifying the shell to kill
            - Attempts graceful termination (SIGTERM) first, then forces (SIGKILL) if needed
            - Returns the final status and any remaining output before termination
            - Cleans up all resources associated with the shell
            - Use this tool when you need to terminate a long-running shell
            - Shell IDs can be found using the bash tool with run_in_background=true

            Example: bash_kill(bash_id="abc12345")
            """;
    }
    
    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "bash_id", Map.of(
                    "type", "string",
                    "description", "The ID of the background shell to terminate. Shell IDs are returned when starting a command with run_in_background=true."
                )
            ),
            "required", List.of("bash_id")
        );
    }
    
    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            String bashId = (String) args.get("bash_id");
            if (bashId == null || bashId.isEmpty()) {
                BashOutputResult result = new BashOutputResult();
                result.setSuccess(false);
                result.setError("bash_id is required");
                result.setStdout("");
                result.setStderr("");
                result.setExitCode(-1);
                return result;
            }
            
            // 在终止前获取剩余输出
            BackgroundShell bgShell = BackgroundShellManager.get(bashId);
            List<String> remainingLines = new ArrayList<>();
            if (bgShell != null) {
                remainingLines = bgShell.getNewOutput(null);
            }
            
            // 通过管理器终止（处理所有清理工作）
            try {
                bgShell = BackgroundShellManager.terminate(bashId);
            } catch (IllegalArgumentException e) {
                // Shell 未找到
                List<String> availableIds = BackgroundShellManager.getAvailableIds();
                BashOutputResult result = new BashOutputResult();
                result.setSuccess(false);
                result.setError(e.getMessage() + ". Available: " + 
                    (availableIds.isEmpty() ? "none" : String.join(", ", availableIds)));
                result.setStdout("");
                result.setStderr(e.getMessage());
                result.setExitCode(-1);
                return result;
            }
            
            // 获取剩余输出
            String stdout = String.join("\n", remainingLines);
            
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(true);
            result.setStdout(stdout);
            result.setStderr("");
            result.setExitCode(bgShell.getExitCode() != null ? bgShell.getExitCode() : 0);
            result.setBashId(bashId);
            result.formatContent();
            return result;
            
        } catch (Exception e) {
            log.error("Failed to terminate bash shell", e);
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(false);
            result.setError("Failed to terminate bash shell: " + e.getMessage());
            result.setStdout("");
            result.setStderr(e.getMessage());
            result.setExitCode(-1);
            return result;
        }
    }
}

