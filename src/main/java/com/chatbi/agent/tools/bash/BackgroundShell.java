package com.chatbi.agent.tools.bash;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 后台 shell 数据容器
 * 仅用于存储状态和输出的纯数据类
 * IO 操作由外部的 BackgroundShellManager 类统一管理
 */
@Data
public class BackgroundShell {
    private String bashId;
    private String command;
    private Process process;
    private long startTime;
    private List<String> outputLines;
    private int lastReadIndex;
    private String status;
    private Integer exitCode;
    
    public BackgroundShell(String bashId, String command, Process process, long startTime) {
        this.bashId = bashId;
        this.command = command;
        this.process = process;
        this.startTime = startTime;
        this.outputLines = new ArrayList<>();
        this.lastReadIndex = 0;
        this.status = "running";
        this.exitCode = null;
    }
    
    /**
     * 添加新输出行
     */
    public void addOutput(String line) {
        outputLines.add(line);
    }
    
    /**
     * 获取自上次检查以来的新增输出内容，还可选择通过正则表达式对这些内容进行过滤
     */
    public List<String> getNewOutput(String filterPattern) {
        List<String> newLines = new ArrayList<>(
            outputLines.subList(lastReadIndex, outputLines.size())
        );
        lastReadIndex = outputLines.size();
        
        if (filterPattern != null && !filterPattern.isEmpty()) {
            try {
                Pattern pattern = Pattern.compile(filterPattern);
                newLines.removeIf(line -> !pattern.matcher(line).find());
            } catch (PatternSyntaxException e) {
                // 无效的正则表达式，返回所有行
            }
        }
        
        return newLines;
    }
    
    /**
     * 更新进程状态
     */
    public void updateStatus(boolean isAlive, Integer exitCode) {
        if (!isAlive) {
            this.status = (exitCode != null && exitCode == 0) ? "completed" : "failed";
            this.exitCode = exitCode;
        } else {
            this.status = "running";
        }
    }
    
    /**
     * 终止后台进程
     */
    public void terminate() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                // 等待进程终止，最多等待 5 秒
                boolean terminated = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!terminated) {
                    process.destroyForcibly();
                    // 再次等待强制终止
                    process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        this.status = "terminated";
        if (process != null) {
            try {
                this.exitCode = process.exitValue();
            } catch (IllegalThreadStateException e) {
                // 进程可能仍在运行，使用 -1 表示未知状态
                this.exitCode = -1;
            }
        }
    }
}

