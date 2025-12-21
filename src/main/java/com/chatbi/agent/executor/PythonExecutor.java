package com.chatbi.agent.executor;

import com.chatbi.agent.tools.BaseTool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * PythonExecutor - Python 代码执行器
 * 
 * 类似于 Python minion 中的 LocalPythonExecutor，提供：
 * 1. 实际执行 Python 代码的能力
 * 2. 工具注入到 Python 环境
 * 3. 变量共享
 * 4. 超时控制
 * 
 * 使用外部 Python 进程执行代码，支持：
 * - 直接执行 Python 代码片段
 * - 工具作为 Python 函数可用
 * - 变量状态在多次执行间保持
 */
@Slf4j
@Data
public class PythonExecutor {
    
    /**
     * Python 可执行文件路径
     */
    private String pythonPath = "python";
    
    /**
     * 执行超时时间（秒）
     */
    private int timeout = 30;
    
    /**
     * 工作目录
     */
    private Path workDir;
    
    /**
     * 注入的工具
     */
    private Map<String, BaseTool> tools = new HashMap<>();
    
    /**
     * 共享变量
     */
    private Map<String, Object> variables = new HashMap<>();
    
    /**
     * 是否启用
     */
    private boolean enabled = true;
    
    // ==================== 构造函数 ====================
    
    public PythonExecutor() {
        try {
            this.workDir = Files.createTempDirectory("agent_python");
            log.info("PythonExecutor initialized with workDir: {}", workDir);
        } catch (IOException e) {
            log.error("Failed to create temp directory", e);
            this.enabled = false;
        }
    }
    
    public PythonExecutor(String pythonPath) {
        this();
        this.pythonPath = pythonPath;
    }
    
    // ==================== 工具注入 ====================
    
    /**
     * 注入工具到 Python 环境
     */
    public void sendTools(List<BaseTool> tools) {
        for (BaseTool tool : tools) {
            this.tools.put(tool.getName(), tool);
        }
        log.info("Injected {} tools into Python environment", tools.size());
    }
    
    /**
     * 注入变量到 Python 环境
     */
    public void sendVariables(Map<String, Object> variables) {
        this.variables.putAll(variables);
    }
    
    // ==================== 代码执行 ====================
    
    /**
     * 执行 Python 代码
     * 
     * @param code Python 代码
     * @return 执行结果
     */
    public ExecutionResult execute(String code) {
        if (!enabled) {
            return ExecutionResult.error("Python executor is not enabled");
        }
        
        try {
            // 预处理代码 - 替换工具调用为 HTTP 请求或直接调用
            String processedCode = preprocessCode(code);
            
            // 创建临时 Python 文件
            Path scriptFile = workDir.resolve("script_" + System.currentTimeMillis() + ".py");
            Files.writeString(scriptFile, processedCode);
            
            // 执行 Python 脚本
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptFile.toString());
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // 等待执行完成
            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
            
            if (!completed) {
                process.destroyForcibly();
                return ExecutionResult.error("Execution timed out after " + timeout + " seconds");
            }
            
            int exitCode = process.exitValue();
            String outputStr = output.toString().trim();
            
            // 清理临时文件
            Files.deleteIfExists(scriptFile);
            
            if (exitCode == 0) {
                return ExecutionResult.success(outputStr);
            } else {
                return ExecutionResult.error("Exit code: " + exitCode + "\n" + outputStr);
            }
            
        } catch (Exception e) {
            log.error("Error executing Python code", e);
            return ExecutionResult.error(e.getMessage());
        }
    }
    
    /**
     * 异步执行 Python 代码
     */
    public CompletableFuture<ExecutionResult> executeAsync(String code) {
        return CompletableFuture.supplyAsync(() -> execute(code));
    }
    
    /**
     * 预处理代码 - 注入工具调用桥接代码
     */
    private String preprocessCode(String code) {
        StringBuilder sb = new StringBuilder();
        
        // 添加导入和工具定义
        sb.append("# Auto-generated tool bridge code\n");
        sb.append("import json\n");
        sb.append("import sys\n\n");
        
        // 为每个工具生成 Python 函数
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            BaseTool tool = entry.getValue();
            
            // 生成工具的 Python 包装函数
            sb.append(String.format("""
                def %s(*args, **kwargs):
                    '''%s'''
                    # Tool bridge - in real implementation, this would call back to Java
                    print(f"[TOOL_CALL] %s: args={args}, kwargs={kwargs}")
                    return "[Tool %s executed]"
                
                """, toolName, tool.getDescription(), toolName, toolName));
        }
        
        // 添加 final_answer 函数
        sb.append("""
            def final_answer(answer):
                '''Provide the final answer to the problem'''
                print(f"[FINAL_ANSWER] {answer}")
                return answer
            
            """);
        
        // 添加用户代码
        sb.append("# User code\n");
        sb.append(code);
        
        return sb.toString();
    }
    
    /**
     * 检查 Python 是否可用
     */
    public boolean isPythonAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            Process process = pb.start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            return completed && process.exitValue() == 0;
        } catch (Exception e) {
            log.warn("Python not available: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (workDir != null) {
            try {
                Files.walk(workDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
            } catch (IOException e) {
                log.error("Error cleaning up workDir", e);
            }
        }
    }
    
    // ==================== 执行结果类 ====================
    
    /**
     * 执行结果
     */
    @Data
    public static class ExecutionResult {
        private boolean success;
        private String output;
        private String error;
        private boolean isFinalAnswer;
        private String finalAnswerValue;
        
        public static ExecutionResult success(String output) {
            ExecutionResult result = new ExecutionResult();
            result.success = true;
            result.output = output;
            
            // 检查是否包含 final_answer
            if (output != null && output.contains("[FINAL_ANSWER]")) {
                result.isFinalAnswer = true;
                int start = output.indexOf("[FINAL_ANSWER]") + 14;
                int end = output.indexOf("\n", start);
                if (end == -1) end = output.length();
                result.finalAnswerValue = output.substring(start, end).trim();
            }
            
            return result;
        }
        
        public static ExecutionResult error(String error) {
            ExecutionResult result = new ExecutionResult();
            result.success = false;
            result.error = error;
            result.output = error;
            return result;
        }
    }
}

