package com.chatbi.agent.tools.bash;

import com.chatbi.agent.tools.Tool;
import com.chatbi.agent.tools.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 从后台 shell 中获取输出
 */
@Slf4j
public class BashOutputTool extends Tool {
    
    @Override
    public String getName() {
        return "bash_output";
    }
    
    @Override
    public String getDescription() {
        return """
            Retrieves output from a running or completed background bash shell.

            - Takes a bash_id parameter identifying the shell
            - Always returns only new output since the last check
            - Returns stdout and stderr output along with shell status
            - Supports optional regex filtering to show only lines matching a pattern
            - Use this tool when you need to monitor or check the output of a long-running shell
            - Shell IDs can be found using the bash tool with run_in_background=true

            Process status values:
              - "running": Still executing
              - "completed": Finished successfully
              - "failed": Finished with error
              - "terminated": Was terminated
              - "error": Error occurred

            Example: bash_output(bash_id="abc12345")
            """;
    }
    
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("bash_id", Map.of(
            "type", "string",
            "description", "The ID of the background shell to retrieve output from. Shell IDs are returned when starting a command with run_in_background=true."
        ));
        properties.put("filter_str", Map.of(
            "type", "string",
            "description", "Optional regular expression to filter the output lines. Only lines matching this regex will be included in the result. Any lines that do not match will no longer be available to read."
        ));
        
        return Map.of(
            "type", "object",
            "properties", properties,
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
            
            String filterStr = (String) args.get("filter_str");
            
            // 从管理器获取后台 shell
            BackgroundShell bgShell = BackgroundShellManager.get(bashId);
            if (bgShell == null) {
                List<String> availableIds = BackgroundShellManager.getAvailableIds();
                BashOutputResult result = new BashOutputResult();
                result.setSuccess(false);
                result.setError("Shell not found: " + bashId + ". Available: " + 
                    (availableIds.isEmpty() ? "none" : String.join(", ", availableIds)));
                result.setStdout("");
                result.setStderr("");
                result.setExitCode(-1);
                return result;
            }
            
            // 获取新输出
            List<String> newLines = bgShell.getNewOutput(filterStr);
            String stdout = String.join("\n", newLines);
            
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(true);
            result.setStdout(stdout);
            result.setStderr(""); // 后台 shell 合并了 stdout/stderr
            result.setExitCode(bgShell.getExitCode() != null ? bgShell.getExitCode() : 0);
            result.setBashId(bashId);
            result.formatContent();
            return result;
            
        } catch (Exception e) {
            log.error("Failed to get bash output", e);
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(false);
            result.setError("Failed to get bash output: " + e.getMessage());
            result.setStdout("");
            result.setStderr(e.getMessage());
            result.setExitCode(-1);
            return result;
        }
    }
}

