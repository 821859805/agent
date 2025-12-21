package com.chatbi.agent.controller;

import com.chatbi.agent.agents.CodeAgent;
import com.chatbi.agent.chatbi_server_java.service.LlmModelFactoryService;
import com.chatbi.agent.service.FileUploadService;
import com.chatbi.agent.tools.SkillTool;
import com.chatbi.agent.types.StreamChunk;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent Stream Controller - SSE 流式输出接口
 * 
 * 提供类似 Python minion 中 GradioUI 的流式输出功能。
 * 使用 Server-Sent Events (SSE) 实时推送处理过程到前端。
 * 
 * 关键特性：
 * - 实时显示 Thought → Code → Observation 过程
 * - 工具调用和结果的实时输出
 * - Skill 加载过程的实时显示
 * - 错误信息的即时反馈
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/stream")
@CrossOrigin(origins = "*")
public class AgentStreamController {
    
    @Autowired(required = false)
    private LlmModelFactoryService llmModelFactoryService;
    
    @Autowired
    private FileUploadService fileUploadService;
    
    /**
     * Agent 实例缓存
     */
    private Map<String, CodeAgent> agents = new ConcurrentHashMap<>();
    
    /**
     * 默认会话 ID
     */
    private static final String DEFAULT_SESSION = "default";
    
    /**
     * 异步执行线程池
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();
    
    // ==================== 流式聊天接口 ====================
    
    /**
     * 流式聊天接口 - 使用 SSE 推送处理过程
     * 
     * 类似于 Python minion 中的 stream_to_gradio 函数
     * 
     * 使用示例：
     * ```javascript
     * const eventSource = new EventSource('/api/agent/stream/chat?message=分析文件&sessionId=123');
     * eventSource.onmessage = (event) => {
     *     const chunk = JSON.parse(event.data);
     *     console.log(chunk.content);
     * };
     * ```
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String message,
            @RequestParam(defaultValue = DEFAULT_SESSION) String sessionId,
            @RequestParam(defaultValue = "10") Integer maxSteps) {
        
        log.info("Received stream chat request: {} for session: {}", message, sessionId);
        
        // 创建 SSE Emitter，超时时间 5 分钟
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        
        executor.execute(() -> {
            try {
                // 获取或创建 Agent
                CodeAgent agent = getOrCreateAgent(sessionId);
                
                // 增强消息，添加文件上下文
                String enhancedMessage = fileUploadService.enhanceMessageWithFiles(message, sessionId);
                
                // 获取已上传的文件列表
                List<String> uploadedFiles = fileUploadService.getSessionFilePaths(sessionId);
                
                // 发送开始消息
                sendChunk(emitter, StreamChunk.builder()
                        .content("🚀 Starting analysis...")
                        .chunkType(StreamChunk.ChunkType.STEP_START)
                        .iteration(0)
                        .maxIterations(maxSteps)
                        .build()
                        .withMetadata("uploaded_files", uploadedFiles));
                
                // 执行流式处理
                agent.runAsyncStream(enhancedMessage, maxSteps, chunk -> {
                    sendChunk(emitter, chunk);
                }).thenAccept(result -> {
                    // 发送最终结果
                    if (result != null) {
                        sendChunk(emitter, StreamChunk.finalAnswer(result.getBestAnswer()));
                    }
                    emitter.complete();
                }).exceptionally(error -> {
                    log.error("Error in stream processing", error);
                    sendChunk(emitter, StreamChunk.error(error.getMessage()));
                    emitter.complete();
                    return null;
                });
                
            } catch (Exception e) {
                log.error("Error starting stream", e);
                sendChunk(emitter, StreamChunk.error(e.getMessage()));
                emitter.complete();
            }
        });
        
        // 设置超时和错误处理
        emitter.onTimeout(() -> {
            log.warn("SSE connection timed out for session: {}", sessionId);
            emitter.complete();
        });
        
        emitter.onError(error -> {
            log.error("SSE error for session: {}", sessionId, error);
        });
        
        return emitter;
    }
    
    /**
     * 发送 SSE 事件
     */
    private void sendChunk(SseEmitter emitter, StreamChunk chunk) {
        try {
            emitter.send(SseEmitter.event()
                    .id(chunk.getId())
                    .name(chunk.getChunkType().name().toLowerCase())
                    .data(chunk.toJson(), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.error("Error sending SSE chunk", e);
        }
    }
    
    /**
     * 流式聊天接口 - POST 版本（支持更长的消息）
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChatPost(@RequestBody StreamChatRequest request) {
        String sessionId = request.getSessionId() != null ? request.getSessionId() : DEFAULT_SESSION;
        Integer maxSteps = request.getMaxSteps() != null ? request.getMaxSteps() : 10;
        
        return streamChat(request.getMessage(), sessionId, maxSteps);
    }
    
    /**
     * 获取或创建 Agent（带流式支持）
     */
    private CodeAgent getOrCreateAgent(String sessionId) {
        return agents.computeIfAbsent(sessionId, id -> {
            log.info("Creating new streaming agent for session: {}", id);
            
            CodeAgent agent = new CodeAgent(llmModelFactoryService, "qwen3-max");
            agent.addTool(new SkillTool());
            agent.setName("Stream-Session-" + id);
            agent.setMaxSteps(10);
            agent.setEnableReflection(true);
            
            agent.setup().join();
            log.info("Streaming agent created for session: {}", id);
            
            return agent;
        });
    }
    
    /**
     * 重置流式会话
     */
    @PostMapping("/reset")
    public Map<String, Object> resetStream(
            @RequestParam(defaultValue = DEFAULT_SESSION) String sessionId) {
        
        CodeAgent agent = agents.remove(sessionId);
        if (agent != null) {
            try {
                agent.close().join();
            } catch (Exception e) {
                log.error("Error closing agent", e);
            }
        }
        
        fileUploadService.clearSessionFiles(sessionId);
        
        return Map.of(
            "success", true,
            "sessionId", sessionId,
            "message", "Stream session reset"
        );
    }
    
    // ==================== 请求 DTO ====================
    
    @Data
    public static class StreamChatRequest {
        private String message;
        private String sessionId;
        private Integer maxSteps;
    }
}

