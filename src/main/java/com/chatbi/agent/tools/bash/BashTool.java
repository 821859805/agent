package com.chatbi.agent.tools.bash;

import com.chatbi.agent.tools.Tool;
import com.chatbi.agent.tools.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 执行 shell 命令
 * 自动检测操作系统类别并使用正确的 shell 命令：
 * - Windows: PowerShell
 * - Unix/Linux/macOS: bash
 */
@Slf4j
public class BashTool extends Tool {
    private final boolean isWindows;
    private final String shellName;
    
    public BashTool() {
        String osName = System.getProperty("os.name");
        this.isWindows = osName != null && osName.toLowerCase().contains("windows");
        this.shellName = isWindows ? "PowerShell" : "bash";
    }
    
    @Override
    public String getName() {
        return "bash";
    }
    
    @Override
    public String getDescription() {
        if (isWindows) {
            return """ 
                Execute PowerShell commands in foreground or background.

                For terminal operations like git, npm, docker, etc. DO NOT use for file operations - use specialized tools.

                Parameters:
                  - command (required): PowerShell command to execute
                  - timeout (optional): Timeout in seconds (default: 120, max: 600) for foreground commands
                  - run_in_background (optional): Set true for long-running commands (servers, etc.)

                Tips:
                  - Quote file paths with spaces: cd "My Documents"
                  - Chain dependent commands with semicolon: git add . ; git commit -m "msg"
                  - Use absolute paths instead of cd when possible
                  - For background commands, monitor with bash_output and terminate with bash_kill

                Examples:
                  - git status
                  - npm test
                  - python -m http.server 8080 (with run_in_background=true)
                """;
        } else {
            return """
                Execute bash commands in foreground or background.

                For terminal operations like git, npm, docker, etc. DO NOT use for file operations - use specialized tools.

                Parameters:
                  - command (required): Bash command to execute
                  - timeout (optional): Timeout in seconds (default: 120, max: 600) for foreground commands
                  - run_in_background (optional): Set true for long-running commands (servers, etc.)

                Tips:
                  - Quote file paths with spaces: cd "My Documents"
                  - Chain dependent commands with &&: git add . && git commit -m "msg"
                  - Use absolute paths instead of cd when possible
                  - For background commands, monitor with bash_output and terminate with bash_kill

                Examples:
                  - git status
                  - npm test
                  - python3 -m http.server 8080 (with run_in_background=true)
                """;
        }
    }
    
    @Override
    public Map<String, Object> getParameters() {
        String cmdDesc = "The " + shellName + " command to execute. Quote file paths with spaces using double quotes.";
        
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
            "type", "string",
            "description", cmdDesc
        ));
        properties.put("timeout", Map.of(
            "type", "integer",
            "description", "Optional: Timeout in seconds (default: 120, max: 600). Only applies to foreground commands.",
            "default", 120
        ));
        properties.put("run_in_background", Map.of(
            "type", "boolean",
            "description", "Optional: Set to true to run the command in the background. Use this for long-running commands like servers. You can monitor output using bash_output tool.",
            "default", false
        ));
        
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", List.of("command")
        );
    }
    
    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            String command = (String) args.get("command");
            if (command == null || command.isEmpty()) {
                BashOutputResult result = new BashOutputResult();
                result.setSuccess(false);
                result.setError("Command is required");
                result.setStdout("");
                result.setStderr("Command is required");
                result.setExitCode(-1);
                return result;
            }
            
            Integer timeout = (Integer) args.getOrDefault("timeout", 120);
            Boolean runInBackground = (Boolean) args.getOrDefault("run_in_background", false);
            
            // 校验时间
            if (timeout > 600) {
                timeout = 600;
            } else if (timeout < 1) {
                timeout = 120;
            }
            
            if (runInBackground != null && runInBackground) {
                return executeInBackground(command);
            } else {
                return executeInForeground(command, timeout);
            }
            
        } catch (Exception e) {
            log.error("Error executing bash command", e);
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setStdout("");
            result.setStderr(e.getMessage());
            result.setExitCode(-1);
            return result;
        }
    }
    
    private BashOutputResult executeInBackground(String command) {
        try {
            String bashId = UUID.randomUUID().toString().substring(0, 8);
            ProcessBuilder processBuilder;
            
            if (isWindows) {
                // Windows: Use PowerShell
                processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
            } else {
                // Unix/Linux/macOS: Use bash
                processBuilder = new ProcessBuilder("/bin/bash", "-c", command);
            }
            
            // 合并 stdout 和 stderr
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            // 创建后台 shell 并添加到管理器
            BackgroundShell bgShell = new BackgroundShell(
                bashId, command, process, System.currentTimeMillis()
            );
            BackgroundShellManager.add(bgShell);
            
            // 开启监控任务
            BackgroundShellManager.startMonitor(bashId);
            
            // 立即返回
            String message = "Command started in background. Use bash_output to monitor (bash_id='" + bashId + "').";
            String formattedContent = message + "\n\nCommand: " + command + "\nBash ID: " + bashId;
            
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(true);
            result.setContent(formattedContent);
            result.setStdout("Background command started with ID: " + bashId);
            result.setStderr("");
            result.setExitCode(0);
            result.setBashId(bashId);
            return result;
            
        } catch (Exception e) {
            log.error("Error executing background command", e);
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setStdout("");
            result.setStderr(e.getMessage());
            result.setExitCode(-1);
            return result;
        }
    }
    
    private BashOutputResult executeInForeground(String command, int timeout) {
        try {
            ProcessBuilder processBuilder;
            
            if (isWindows) {
                // Windows: Use PowerShell
                processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
            } else {
                // Unix/Linux/macOS: Use bash
                processBuilder = new ProcessBuilder("/bin/bash", "-c", command);
            }
            
            Process process = processBuilder.start();
            
            // 读取 stdout 和 stderr
            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();
            
            try (BufferedReader stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                
                // 使用线程读取输出
                Thread stdoutThread = new Thread(() -> {
                    try {
                        String line;
                        while ((line = stdoutReader.readLine()) != null) {
                            stdoutBuilder.append(line).append("\n");
                        }
                    } catch (Exception e) {
                        log.error("Error reading stdout", e);
                    }
                });
                
                Thread stderrThread = new Thread(() -> {
                    try {
                        String line;
                        while ((line = stderrReader.readLine()) != null) {
                            stderrBuilder.append(line).append("\n");
                        }
                    } catch (Exception e) {
                        log.error("Error reading stderr", e);
                    }
                });
                
                stdoutThread.start();
                stderrThread.start();
                
                // 等待进程完成或超时
                boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
                
                if (!finished) {
                    process.destroyForcibly();
                    BashOutputResult result = new BashOutputResult();
                    result.setSuccess(false);
                    result.setError("Command timed out after " + timeout + " seconds");
                    result.setStdout("");
                    result.setStderr("Command timed out after " + timeout + " seconds");
                    result.setExitCode(-1);
                    return result;
                }
                
                // 等待读取线程完成
                stdoutThread.join(1000);
                stderrThread.join(1000);
            }
            
            // 解码输出
            String stdoutText = stdoutBuilder.toString();
            String stderrText = stderrBuilder.toString();
            
            // 创建结果
            int exitCode = process.exitValue();
            boolean isSuccess = exitCode == 0;
            String errorMsg = null;
            if (!isSuccess) {
                errorMsg = "Command failed with exit code " + exitCode;
                if (stderrText != null && !stderrText.trim().isEmpty()) {
                    errorMsg += "\n" + stderrText.trim();
                }
            }
            
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(isSuccess);
            result.setError(errorMsg);
            result.setStdout(stdoutText);
            result.setStderr(stderrText);
            result.setExitCode(exitCode);
            result.formatContent();
            return result;
            
        } catch (Exception e) {
            log.error("Error executing foreground command", e);
            BashOutputResult result = new BashOutputResult();
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setStdout("");
            result.setStderr(e.getMessage());
            result.setExitCode(-1);
            return result;
        }
    }
}

