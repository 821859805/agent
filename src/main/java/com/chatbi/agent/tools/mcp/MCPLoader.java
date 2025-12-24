package com.chatbi.agent.tools.mcp;

import com.chatbi.agent.tools.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * MCP 工具加载器
 * 
 * 从配置文件加载 MCP 工具：
 * 1. 读取 MCP 配置文件
 * 2. 启动 MCP 服务器进程
 * 3. 连接到每个服务器
 * 4. 获取工具定义
 * 5. 包装为 Tool 对象
 */
@Slf4j
public class MCPLoader {
    private static final List<MCPServerConnection> connections = new ArrayList<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从配置文件加载 MCP 工具
     * 
     * @param configPath MCP 配置文件路径（默认: "mcp.json"）
     * @return Tool 对象列表
     */
    public static List<Tool> loadMCPTools(String configPath) {
        return loadMCPTools(configPath != null ? Paths.get(configPath) : Paths.get("mcp.json"));
    }

    /**
     * 从配置文件加载 MCP 工具
     * 
     * @param configPath MCP 配置文件路径
     * @return Tool 对象列表
     */
    @SuppressWarnings("unchecked")
    public static List<Tool> loadMCPTools(Path configPath) {
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
            log.warn("MCP config not found: {}", configPath);
            return Collections.emptyList();
        }

        try {
            // 读取配置文件
            Map<String, Object> config = objectMapper.readValue(configFile, Map.class);
            Map<String, Object> mcpServers = (Map<String, Object>) config.get("mcpServers");

            if (mcpServers == null || mcpServers.isEmpty()) {
                log.info("No MCP servers configured");
                return Collections.emptyList();
            }

            List<Tool> allTools = new ArrayList<>();

            // 连接到每个启用的服务器
            for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
                String serverName = entry.getKey();
                Map<String, Object> serverConfig = (Map<String, Object>) entry.getValue();

                // 检查是否禁用
                Boolean disabled = (Boolean) serverConfig.get("disabled");
                if (Boolean.TRUE.equals(disabled)) {
                    log.info("Skipping disabled server: {}", serverName);
                    continue;
                }

                // 获取命令和参数
                String command = (String) serverConfig.get("command");
                List<String> args = (List<String>) serverConfig.get("args");
                Map<String, String> env = extractEnv(serverConfig.get("env"));

                if (command == null || command.isEmpty()) {
                    log.warn("No command specified for server: {}", serverName);
                    continue;
                }

                // 创建连接并连接
                MCPServerConnection connection = new MCPServerConnection(
                    serverName, command, args, env
                );

                boolean success = connection.connect();

                if (success) {
                    connections.add(connection);
                    allTools.addAll(connection.getTools());
                }
            }

            log.info("\nTotal MCP tools loaded: {}", allTools.size());

            return allTools;

        } catch (Exception e) {
            log.error("Error loading MCP config: {}", configPath, e);
            return Collections.emptyList();
        }
    }

    /**
     * 提取环境变量
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> extractEnv(Object envObj) {
        if (envObj == null) {
            return Collections.emptyMap();
        }

        if (envObj instanceof Map) {
            Map<String, Object> envMap = (Map<String, Object>) envObj;
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : envMap.entrySet()) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return result;
        }

        return Collections.emptyMap();
    }

    /**
     * 清理所有 MCP 连接
     */
    public static void cleanupConnections() {
        log.info("Cleaning up MCP connections...");
        for (MCPServerConnection connection : connections) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting from server: {}", connection.getName(), e);
            }
        }
        connections.clear();
    }

    /**
     * 获取当前活动的连接
     */
    public static List<MCPServerConnection> getConnections() {
        return new ArrayList<>(connections);
    }
}
