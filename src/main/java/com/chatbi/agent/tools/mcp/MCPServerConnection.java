package com.chatbi.agent.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理单个 MCP 服务器的连接
 */
@Slf4j
public class MCPServerConnection {
    private final String name;
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final Map<String, CompletableFuture<MCPResponse>> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean connected = false;
    private final List<MCPTool> tools = new ArrayList<>();

    public MCPServerConnection(String name, String command, List<String> args, Map<String, String> env) {
        this.name = name;
        this.command = command;
        this.args = args != null ? args : Collections.emptyList();
        this.env = env != null ? env : Collections.emptyMap();
    }

    /**
     * 连接到 MCP 服务器
     */
    public boolean connect() {
        try {
            // 构建进程命令
            List<String> commandList = new ArrayList<>();
            commandList.add(command);
            commandList.addAll(args);

            ProcessBuilder processBuilder = new ProcessBuilder(commandList);
            
            // 设置环境变量
            Map<String, String> processEnv = processBuilder.environment();
            processEnv.putAll(env);

            // 启动进程
            process = processBuilder.start();

            // 设置输入输出流
            writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
            );
            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );

            // 启动读取线程
            readerThread = new Thread(this::readResponses);
            readerThread.setDaemon(true);
            readerThread.start();

            // 初始化会话
            if (!initialize()) {
                disconnect();
                return false;
            }

            // 列出可用工具
            List<MCPToolDefinition> toolDefinitions = listTools();
            if (toolDefinitions == null) {
                disconnect();
                return false;
            }

            // 包装每个工具
            for (MCPToolDefinition toolDef : toolDefinitions) {
                MCPTool tool = new MCPTool(
                    toolDef.getName(),
                    toolDef.getDescription(),
                    toolDef.getInputSchema(),
                    this
                );
                tools.add(tool);
            }

            connected = true;
            log.info("✓ Connected to MCP server '{}' - loaded {} tools", name, tools.size());
            for (MCPTool tool : tools) {
                String desc = tool.getDescription();
                if (desc.length() > 60) {
                    desc = desc.substring(0, 60) + "...";
                }
                log.info("  - {}: {}", tool.getName(), desc);
            }

            return true;

        } catch (Exception e) {
            log.error("✗ Failed to connect to MCP server '{}': {}", name, e.getMessage(), e);
            disconnect();
            return false;
        }
    }

    /**
     * 初始化 MCP 会话
     */
    private boolean initialize() {
        try {
            Map<String, Object> initRequest = new HashMap<>();
            initRequest.put("jsonrpc", "2.0");
            initRequest.put("id", requestIdCounter.getAndIncrement());
            initRequest.put("method", "initialize");
            initRequest.put("params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                    "name", "chatbi-agent",
                    "version", "1.0.0"
                )
            ));

            MCPResponse response = sendRequest(initRequest);
            if (response == null || response.getError() != null) {
                log.error("Failed to initialize MCP session: {}", 
                    response != null ? response.getError() : "No response");
                return false;
            }

            // 发送 initialized 通知
            Map<String, Object> initializedNotification = new HashMap<>();
            initializedNotification.put("jsonrpc", "2.0");
            initializedNotification.put("method", "notifications/initialized");
            initializedNotification.put("params", Map.of());

            sendNotification(initializedNotification);

            return true;

        } catch (Exception e) {
            log.error("Error initializing MCP session", e);
            return false;
        }
    }

    /**
     * 列出可用工具
     */
    @SuppressWarnings("unchecked")
    private List<MCPToolDefinition> listTools() {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIdCounter.getAndIncrement());
            request.put("method", "tools/list");
            request.put("params", Map.of());

            MCPResponse response = sendRequest(request);
            if (response == null || response.getError() != null) {
                log.error("Failed to list tools: {}", 
                    response != null ? response.getError() : "No response");
                return null;
            }

            // 解析工具列表
            if (response.getResult() instanceof Map) {
                Map<String, Object> result = (Map<String, Object>) response.getResult();
                Object toolsObj = result.get("tools");
                if (toolsObj instanceof List) {
                    List<Map<String, Object>> toolsList = (List<Map<String, Object>>) toolsObj;
                    List<MCPToolDefinition> toolDefinitions = new ArrayList<>();
                    
                    for (Map<String, Object> toolMap : toolsList) {
                        MCPToolDefinition toolDef = new MCPToolDefinition();
                        toolDef.setName((String) toolMap.get("name"));
                        toolDef.setDescription((String) toolMap.get("description"));
                        
                        Object inputSchema = toolMap.get("inputSchema");
                        if (inputSchema instanceof Map) {
                            toolDef.setInputSchema((Map<String, Object>) inputSchema);
                        } else {
                            toolDef.setInputSchema(Map.of());
                        }
                        
                        toolDefinitions.add(toolDef);
                    }
                    
                    return toolDefinitions;
                }
            }

            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Error listing tools", e);
            return null;
        }
    }

    /**
     * 调用 MCP 工具
     */
    public MCPResponse callTool(String toolName, Map<String, Object> arguments) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIdCounter.getAndIncrement());
            request.put("method", "tools/call");
            request.put("params", Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
            ));

            MCPResponse response = sendRequest(request);
            if (response != null) {
                response.extractContent();
            }
            return response;

        } catch (Exception e) {
            log.error("Error calling tool: {}", toolName, e);
            return null;
        }
    }

    /**
     * 发送 JSON-RPC 请求并等待响应
     */
    private MCPResponse sendRequest(Map<String, Object> request) throws Exception {
        String id = String.valueOf(request.get("id"));
        CompletableFuture<MCPResponse> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            // 发送请求
            String json = objectMapper.writeValueAsString(request);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            // 等待响应（最多 30 秒）
            MCPResponse response = future.get(30, TimeUnit.SECONDS);
            return response;

        } catch (TimeoutException e) {
            log.error("Request timeout: {}", id);
            pendingRequests.remove(id);
            return null;
        } catch (Exception e) {
            log.error("Error sending request", e);
            pendingRequests.remove(id);
            throw e;
        }
    }

    /**
     * 发送 JSON-RPC 通知（不需要响应）
     */
    private void sendNotification(Map<String, Object> notification) {
        try {
            String json = objectMapper.writeValueAsString(notification);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (Exception e) {
            log.error("Error sending notification", e);
        }
    }

    /**
     * 读取响应线程
     */
    private void readResponses() {
        try {
            String line;
            while ((line = reader.readLine()) != null && connected) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                try {
                    // 解析 JSON-RPC 响应
                    Map<String, Object> responseMap = objectMapper.readValue(line, Map.class);
                    MCPResponse response = new MCPResponse();
                    
                    // 设置基本字段
                    response.setJsonrpc((String) responseMap.get("jsonrpc"));
                    Object idObj = responseMap.get("id");
                    if (idObj != null) {
                        response.setId(String.valueOf(idObj));
                    }
                    
                    // 处理 result 或 error
                    if (responseMap.containsKey("result")) {
                        response.setResult(responseMap.get("result"));
                        response.extractContent();
                    }
                    
                    if (responseMap.containsKey("error")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> errorMap = (Map<String, Object>) responseMap.get("error");
                        MCPResponse.MCPError error = new MCPResponse.MCPError();
                        if (errorMap != null) {
                            Object code = errorMap.get("code");
                            if (code instanceof Number) {
                                error.setCode(((Number) code).intValue());
                            }
                            error.setMessage((String) errorMap.get("message"));
                            error.setData(errorMap.get("data"));
                        }
                        response.setError(error);
                        response.setIsError(true);
                    }
                    
                    String id = response.getId();
                    if (id != null) {
                        CompletableFuture<MCPResponse> future = pendingRequests.remove(id);
                        if (future != null) {
                            future.complete(response);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse MCP response: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (connected) {
                log.error("Error reading from MCP server", e);
            }
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        connected = false;

        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }

        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.warn("Error closing writer", e);
            }
        }

        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                log.warn("Error closing reader", e);
            }
        }

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 取消所有待处理的请求
        for (CompletableFuture<MCPResponse> future : pendingRequests.values()) {
            future.cancel(true);
        }
        pendingRequests.clear();
    }

    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    public List<MCPTool> getTools() {
        return new ArrayList<>(tools);
    }

    public String getName() {
        return name;
    }
}
