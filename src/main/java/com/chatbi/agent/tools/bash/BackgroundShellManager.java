package com.chatbi.agent.tools.bash;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 管理全部的后台 shell 进程
 */
@Slf4j
public class BackgroundShellManager {
    private static final Map<String, BackgroundShell> shells = new ConcurrentHashMap<>();
    private static final Map<String, Future<?>> monitorTasks = new ConcurrentHashMap<>();
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    
    /**
     * 添加一个后台 shell 进程
     */
    public static void add(BackgroundShell shell) {
        shells.put(shell.getBashId(), shell);
    }
    
    /**
     * 通过 ID 获得后台 shell
     */
    public static BackgroundShell get(String bashId) {
        return shells.get(bashId);
    }
    
    /**
     * 获取全部可用的 shell id
     */
    public static List<String> getAvailableIds() {
        return new ArrayList<>(shells.keySet());
    }
    
    /**
     * 删除一个后台 shell（仅内部使用）
     */
    private static void remove(String bashId) {
        shells.remove(bashId);
    }
    
    /**
     * 开始监控 shell 的输出
     */
    public static void startMonitor(String bashId) {
        BackgroundShell shell = get(bashId);
        if (shell == null) {
            return;
        }
        
        Future<?> task = executorService.submit(() -> {
            try {
                Process process = shell.getProcess();
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                );
                
                // 持续读输出，直到进程终止
                String line;
                while (process.isAlive()) {
                    try {
                        // 使用非阻塞读取
                        if (reader.ready()) {
                            line = reader.readLine();
                            if (line != null) {
                                shell.addOutput(line);
                            }
                        } else {
                            Thread.sleep(100); // 等待 100ms
                        }
                    } catch (Exception e) {
                        Thread.sleep(100);
                        continue;
                    }
                }
                
                // 进程已终止，读取剩余输出
                try {
                    while ((line = reader.readLine()) != null) {
                        shell.addOutput(line);
                    }
                } catch (Exception e) {
                    // 忽略读取错误
                    log.debug("Error reading remaining output: {}", e.getMessage());
                }
                
                // 获取退出代码
                int returncode = process.waitFor();
                
                // 更新状态
                shell.updateStatus(false, returncode);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Monitor interrupted for shell {}", bashId);
                if (shells.containsKey(bashId)) {
                    shells.get(bashId).setStatus("error");
                    shells.get(bashId).addOutput("Monitor interrupted");
                }
            } catch (Exception e) {
                log.error("Monitor error for shell {}: {}", bashId, e.getMessage(), e);
                if (shells.containsKey(bashId)) {
                    shells.get(bashId).setStatus("error");
                    shells.get(bashId).addOutput("Monitor error: " + e.getMessage());
                }
            } finally {
                monitorTasks.remove(bashId);
            }
        });
        
        monitorTasks.put(bashId, task);
    }
    
    /**
     * 取消并且移除一个监控任务（仅内部使用）
     */
    private static void cancelMonitor(String bashId) {
        Future<?> task = monitorTasks.remove(bashId);
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }
    
    /**
     * 终止一个后台 shell，清空全部资源
     *
     * @param bashId 后台 shell 的唯一标识符
     * @return 已终止的 BackgroundShell 对象
     * @throws IllegalArgumentException 如果 shell 未找到
     */
    public static BackgroundShell terminate(String bashId) {
        BackgroundShell shell = get(bashId);
        if (shell == null) {
            throw new IllegalArgumentException("Shell not found: " + bashId);
        }
        
        // 终止进程
        shell.terminate();
        
        // 取消监控任务，从 manager 中移除
        cancelMonitor(bashId);
        remove(bashId);
        
        return shell;
    }
}

