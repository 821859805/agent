package com.chatbi.agent.controller;

import com.chatbi.agent.agents.CodeAgent;
import com.chatbi.agent.chatbi_server_java.service.LlmModelFactoryService;
import com.chatbi.agent.service.FileUploadService;
import com.chatbi.agent.tools.BaseTool;
import com.chatbi.agent.tools.FinalAnswerTool;
import com.chatbi.agent.tools.SkillTool;
import com.chatbi.agent.types.AgentResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent REST API Controller
 * 
 * 提供类似Gradio的Web API接口，用于与Agent交互。
 * 支持文件上传和分析功能，类似于Python minion中的gradio_demo.py
 * 
 * Endpoints:
 * - POST /api/agent/chat - 发送消息给Agent
 * - POST /api/agent/upload - 上传文件
 * - GET /api/agent/files - 获取已上传的文件列表
 * - GET /api/agent/status - 获取Agent状态
 * - POST /api/agent/reset - 重置Agent
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {
    
    @Autowired(required = false)
    private LlmModelFactoryService llmModelFactoryService;
    
    @Autowired
    private FileUploadService fileUploadService;
    
    /**
     * Agent实例（每个会话一个）
     */
    private Map<String, CodeAgent> agents = new ConcurrentHashMap<>();
    
    /**
     * 默认会话ID
     */
    private static final String DEFAULT_SESSION = "default";
    
    @PostConstruct
    public void init() {
        log.info("AgentController initialized with file upload support");
    }
    
    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up agents...");
        agents.values().forEach(agent -> {
            try {
                agent.close().join();
            } catch (Exception e) {
                log.error("Error closing agent", e);
            }
        });
        agents.clear();
    }
    
    // ==================== 文件上传接口 ====================
    
    /**
     * 上传文件
     * 
     * 类似于Python minion中GradioUI的upload_file方法
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", defaultValue = DEFAULT_SESSION) String sessionId) {
        
        log.info("Uploading file: {} for session: {}", file.getOriginalFilename(), sessionId);
        
        FileUploadService.UploadResult result = fileUploadService.uploadFile(file, sessionId);
        
        Map<String, Object> response = result.toMap();
        response.put("sessionId", sessionId);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 批量上传文件
     */
    @PostMapping(value = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFiles(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", defaultValue = DEFAULT_SESSION) String sessionId) {
        
        log.info("Uploading {} files for session: {}", files.size(), sessionId);
        
        List<FileUploadService.UploadResult> results = fileUploadService.uploadFiles(files, sessionId);
        
        List<Map<String, Object>> resultMaps = new ArrayList<>();
        int successCount = 0;
        for (FileUploadService.UploadResult result : results) {
            resultMaps.add(result.toMap());
            if (result.isSuccess()) successCount++;
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", successCount > 0);
        response.put("sessionId", sessionId);
        response.put("totalFiles", files.size());
        response.put("successCount", successCount);
        response.put("results", resultMaps);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取会话已上传的文件列表
     */
    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> getFiles(
            @RequestParam(value = "sessionId", defaultValue = DEFAULT_SESSION) String sessionId) {
        
        List<String> files = fileUploadService.getSessionFilePaths(sessionId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("files", files);
        response.put("count", files.size());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 清除会话的上传文件
     */
    @DeleteMapping("/files")
    public ResponseEntity<Map<String, Object>> clearFiles(
            @RequestParam(value = "sessionId", defaultValue = DEFAULT_SESSION) String sessionId) {
        
        fileUploadService.clearSessionFiles(sessionId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("message", "Files cleared");
        
        return ResponseEntity.ok(response);
    }
    
    // ==================== 聊天接口 ====================
    
    /**
     * 发送消息给Agent
     * 
     * 会自动将上传的文件信息添加到消息中，类似于Python中的log_user_message
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("Received chat request: {}", request.getMessage());
        
        String sessionId = request.getSessionId() != null ? request.getSessionId() : DEFAULT_SESSION;
        
        try {
            // 获取或创建Agent
            CodeAgent agent = getOrCreateAgent(sessionId);
            
            // 增强消息，添加文件上下文（类似Python中的log_user_message）
            String enhancedMessage = fileUploadService.enhanceMessageWithFiles(
                request.getMessage(), sessionId);
            
            log.debug("Enhanced message: {}", enhancedMessage);
            
            // 运行任务
            Object result = agent.runAsync(enhancedMessage, request.getMaxSteps()).join();
            
            // 构建响应
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setSessionId(sessionId);
            response.setUploadedFiles(fileUploadService.getSessionFilePaths(sessionId));
            
            if (result instanceof AgentResponse) {
                AgentResponse agentResponse = (AgentResponse) result;
                response.setMessage(agentResponse.getBestAnswer());
                response.setThinkContent(agentResponse.getThinkContent());
                response.setIsFinalAnswer(agentResponse.getIsFinalAnswer());
            } else {
                response.setMessage(result != null ? result.toString() : "No response");
            }
            
            log.info("Chat response: {}", response.getMessage());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error processing chat request", e);
            ChatResponse errorResponse = new ChatResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Error: " + e.getMessage());
            errorResponse.setSessionId(sessionId);
            return ResponseEntity.ok(errorResponse);
        }
    }
    
    /**
     * 异步发送消息
     */
    @PostMapping(value = "/chat/async", produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<ChatResponse>> chatAsync(@RequestBody ChatRequest request) {
        log.info("Received async chat request: {}", request.getMessage());
        
        String sessionId = request.getSessionId() != null ? request.getSessionId() : DEFAULT_SESSION;
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                CodeAgent agent = getOrCreateAgent(sessionId);
                
                // 增强消息，添加文件上下文
                String enhancedMessage = fileUploadService.enhanceMessageWithFiles(
                    request.getMessage(), sessionId);
                
                Object result = agent.runAsync(enhancedMessage, request.getMaxSteps()).join();
                
                ChatResponse response = new ChatResponse();
                response.setSuccess(true);
                response.setSessionId(sessionId);
                response.setUploadedFiles(fileUploadService.getSessionFilePaths(sessionId));
                
                if (result instanceof AgentResponse) {
                    AgentResponse agentResponse = (AgentResponse) result;
                    response.setMessage(agentResponse.getBestAnswer());
                    response.setThinkContent(agentResponse.getThinkContent());
                    response.setIsFinalAnswer(agentResponse.getIsFinalAnswer());
                } else {
                    response.setMessage(result != null ? result.toString() : "No response");
                }
                
                return ResponseEntity.ok(response);
                
            } catch (Exception e) {
                log.error("Error processing async chat request", e);
                ChatResponse errorResponse = new ChatResponse();
                errorResponse.setSuccess(false);
                errorResponse.setMessage("Error: " + e.getMessage());
                errorResponse.setSessionId(sessionId);
                return ResponseEntity.ok(errorResponse);
            }
        });
    }
    
    // ==================== 状态管理接口 ====================
    
    /**
     * 获取Agent状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestParam(defaultValue = DEFAULT_SESSION) String sessionId) {
        
        Map<String, Object> status = new HashMap<>();
        status.put("sessionId", sessionId);
        
        CodeAgent agent = agents.get(sessionId);
        if (agent != null) {
            status.put("agentExists", true);
            status.put("agentName", agent.getName());
            status.put("toolsCount", agent.getTools().size());
            status.put("maxSteps", agent.getMaxSteps());
            status.put("reflectionEnabled", agent.getEnableReflection());
            
            if (agent.getState() != null) {
                status.put("currentStep", agent.getState().getStepCount());
                status.put("errorCount", agent.getState().getErrorCount());
            }
        } else {
            status.put("agentExists", false);
        }
        
        // 添加文件信息
        List<String> files = fileUploadService.getSessionFilePaths(sessionId);
        status.put("uploadedFiles", files);
        status.put("uploadedFilesCount", files.size());
        
        status.put("llmServiceAvailable", llmModelFactoryService != null);
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 重置Agent（同时清除上传的文件）
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(
            @RequestParam(defaultValue = DEFAULT_SESSION) String sessionId,
            @RequestParam(defaultValue = "true") boolean clearFiles) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        
        CodeAgent agent = agents.get(sessionId);
        if (agent != null) {
            try {
                agent.close().join();
                agents.remove(sessionId);
                result.put("agentReset", true);
            } catch (Exception e) {
                result.put("agentReset", false);
                result.put("agentResetError", e.getMessage());
            }
        } else {
            result.put("agentReset", true);
            result.put("message", "No agent to reset");
        }
        
        // 清除文件
        if (clearFiles) {
            fileUploadService.clearSessionFiles(sessionId);
            result.put("filesCleared", true);
        }
        
        result.put("success", true);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("llmServiceAvailable", llmModelFactoryService != null);
        health.put("activeSessions", agents.size());
        health.put("uploadFolder", fileUploadService.getUploadPath().toString());
        return ResponseEntity.ok(health);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取或创建Agent
     */
    private CodeAgent getOrCreateAgent(String sessionId) {
        return agents.computeIfAbsent(sessionId, id -> {
            log.info("Creating new agent for session: {}", id);
            
            CodeAgent agent = new CodeAgent(llmModelFactoryService, "qwen3-max");
            agent.addTool(new SkillTool());

            agent.setName("Session-" + id);
            agent.setMaxSteps(10);
            agent.setEnableReflection(true);
            
            agent.setup().join();
            log.info("Agent created and initialized for session: {}", id);
            
            return agent;
        });
    }
    
    // ==================== DTO类 ====================
    
    /**
     * 聊天请求
     */
    @Data
    public static class ChatRequest {
        private String message;
        private String sessionId;
        private Integer maxSteps = 10;
    }
    
    /**
     * 聊天响应
     */
    @Data
    public static class ChatResponse {
        private boolean success;
        private String message;
        private String sessionId;
        private String thinkContent;
        private Boolean isFinalAnswer;
        private List<String> uploadedFiles;  // 已上传的文件列表
    }
}
