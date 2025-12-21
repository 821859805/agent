package com.chatbi.agent.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * FileUploadService - 文件上传服务
 * 
 * 类似于Python minion中GradioUI的文件上传功能：
 * - 保存上传的文件到指定目录
 * - 跟踪每个会话上传的文件
 * - 生成文件上下文信息用于Agent分析
 */
@Slf4j
@Service
public class FileUploadService {
    
    /**
     * 上传目录
     */
    @Value("${agent.upload.folder:uploads}")
    private String uploadFolder;
    
    /**
     * 最大文件大小（字节）
     */
    @Value("${agent.upload.max-size:10485760}")
    private long maxFileSize; // 默认10MB
    
    /**
     * 允许的文件类型
     */
    private static final Set<String> ALLOWED_TYPES = Set.of(
        ".pdf", ".docx", ".txt", ".py", ".md", ".json", ".csv", 
        ".xlsx", ".xls", ".xml", ".html", ".java", ".js", ".ts",
        ".yaml", ".yml", ".sql", ".log", ".conf", ".ini"
    );
    
    /**
     * 上传目录路径
     */
    private Path uploadPath;
    
    /**
     * 会话文件映射 (sessionId -> 文件路径列表)
     */
    private final Map<String, List<String>> sessionFiles = new ConcurrentHashMap<>();
    
    /**
     * 文件名清理正则
     */
    private static final Pattern SANITIZE_PATTERN = Pattern.compile("[^\\w\\-.]");
    
    // ==================== 初始化 ====================
    
    @PostConstruct
    public void init() throws IOException {
        uploadPath = Paths.get(uploadFolder);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
            log.info("Created upload directory: {}", uploadPath.toAbsolutePath());
        }
        log.info("FileUploadService initialized. Upload folder: {}", uploadPath.toAbsolutePath());
    }
    
    // ==================== 上传方法 ====================
    
    /**
     * 上传文件
     * 
     * @param file MultipartFile
     * @param sessionId 会话ID
     * @return 上传结果
     */
    public UploadResult uploadFile(MultipartFile file, String sessionId) {
        if (file == null || file.isEmpty()) {
            return UploadResult.error("No file provided");
        }
        
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isEmpty()) {
            return UploadResult.error("Invalid file name");
        }
        
        // 检查文件大小
        if (file.getSize() > maxFileSize) {
            return UploadResult.error(String.format("File too large. Max size: %d MB", maxFileSize / 1024 / 1024));
        }
        
        // 检查文件类型
        String ext = getFileExtension(originalName).toLowerCase();
        if (!ALLOWED_TYPES.contains(ext)) {
            return UploadResult.error(String.format("File type %s not allowed", ext));
        }
        
        try {
            // 清理文件名
            String sanitizedName = sanitizeFileName(originalName);
            
            // 确保会话目录存在
            Path sessionPath = uploadPath.resolve(sessionId);
            if (!Files.exists(sessionPath)) {
                Files.createDirectories(sessionPath);
            }
            
            // 保存文件
            Path filePath = sessionPath.resolve(sanitizedName);
            
            // 如果文件已存在，添加时间戳
            if (Files.exists(filePath)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
                String nameWithoutExt = sanitizedName.substring(0, sanitizedName.lastIndexOf('.'));
                sanitizedName = nameWithoutExt + "_" + timestamp + ext;
                filePath = sessionPath.resolve(sanitizedName);
            }
            
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            
            // 添加到会话文件列表
            String absolutePath = filePath.toAbsolutePath().toString();
            sessionFiles.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(absolutePath);
            
            log.info("File uploaded: {} -> {} (session: {})", originalName, absolutePath, sessionId);
            
            return UploadResult.success(absolutePath, originalName);
            
        } catch (IOException e) {
            log.error("Failed to upload file: {}", originalName, e);
            return UploadResult.error("Failed to save file: " + e.getMessage());
        }
    }
    
    /**
     * 批量上传文件
     */
    public List<UploadResult> uploadFiles(List<MultipartFile> files, String sessionId) {
        List<UploadResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(uploadFile(file, sessionId));
        }
        return results;
    }
    
    // ==================== 会话管理 ====================
    
    /**
     * 获取会话的所有文件路径
     */
    public List<String> getSessionFilePaths(String sessionId) {
        return new ArrayList<>(sessionFiles.getOrDefault(sessionId, new ArrayList<>()));
    }
    
    /**
     * 生成文件上下文提示词
     * 
     * 类似于Python中的：
     * f"\nYou have been provided with these files, which might be helpful or not: {file_uploads_log}"
     */
    public String generateFileContext(String sessionId) {
        List<String> files = getSessionFilePaths(sessionId);
        
        if (files.isEmpty()) {
            return "";
        }
        
        return "\nYou have been provided with these files, which might be helpful or not: " + files;
    }
    
    /**
     * 增强用户消息，添加文件上下文
     * 
     * @param userMessage 原始用户消息
     * @param sessionId 会话ID
     * @return 增强后的消息（包含文件路径信息）
     */
    public String enhanceMessageWithFiles(String userMessage, String sessionId) {
        String fileContext = generateFileContext(sessionId);
        if (fileContext.isEmpty()) {
            return userMessage;
        }
        return userMessage + fileContext;
    }
    
    /**
     * 清理会话文件
     */
    public void clearSessionFiles(String sessionId) {
        List<String> files = sessionFiles.remove(sessionId);
        
        if (files != null && !files.isEmpty()) {
            for (String filePath : files) {
                try {
                    Files.deleteIfExists(Paths.get(filePath));
                } catch (IOException e) {
                    log.warn("Failed to delete file: {}", filePath, e);
                }
            }
            
            // 尝试删除会话目录
            try {
                Path sessionPath = uploadPath.resolve(sessionId);
                if (Files.exists(sessionPath) && Files.isDirectory(sessionPath)) {
                    Files.deleteIfExists(sessionPath);
                }
            } catch (IOException e) {
                log.warn("Failed to delete session directory: {}", sessionId, e);
            }
        }
        
        log.info("Cleared files for session: {}", sessionId);
    }
    
    // ==================== 工具方法 ====================
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            return fileName.substring(lastDot);
        }
        return "";
    }
    
    private String sanitizeFileName(String fileName) {
        return SANITIZE_PATTERN.matcher(fileName).replaceAll("_");
    }
    
    public Path getUploadPath() {
        return uploadPath;
    }
    
    // ==================== 数据类 ====================
    
    /**
     * 上传结果
     */
    @Data
    public static class UploadResult {
        private boolean success;
        private String message;
        private String filePath;
        private String originalName;
        
        public static UploadResult success(String filePath, String originalName) {
            UploadResult result = new UploadResult();
            result.setSuccess(true);
            result.setMessage("File uploaded: " + filePath);
            result.setFilePath(filePath);
            result.setOriginalName(originalName);
            return result;
        }
        
        public static UploadResult error(String message) {
            UploadResult result = new UploadResult();
            result.setSuccess(false);
            result.setMessage(message);
            return result;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("success", success);
            map.put("message", message);
            if (filePath != null) map.put("filePath", filePath);
            if (originalName != null) map.put("originalName", originalName);
            return map;
        }
    }
}

