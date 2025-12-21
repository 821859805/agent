package com.chatbi.agent.chatbi_server_java.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "llm_model_config")
public class LlmModelConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 100)
    private String name;
    
    @Column(name = "model_id", nullable = false, length = 100)
    @JsonProperty("model_id")
    private String modelId;
    
    @Column(nullable = false, length = 50)
    private String provider = "ollama";
    
    @Column(name = "base_url", length = 255)
    @JsonProperty("base_url")
    private String baseUrl;
    
    @Column(name = "api_key", length = 255)
    @JsonProperty("api_key")
    private String apiKey;
    
    @Column(name = "model_type", length = 50)
    @JsonProperty("model_type")
    private String modelType = "chat";
    
    @Column(name = "max_tokens")
    @JsonProperty("max_tokens")
    private Integer maxTokens = 4096;
    
    @Column(precision = 3, scale = 2)
    private BigDecimal temperature = new BigDecimal("0.10");
    
    @Column(name = "timeout_seconds")
    @JsonProperty("timeout_seconds")
    private Integer timeoutSeconds = 120;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "is_enabled")
    @JsonProperty("is_enabled")
    private Boolean isEnabled = true;
    
    @Column(name = "is_default")
    @JsonProperty("is_default")
    private Boolean isDefault = false;
    
    @Column(name = "is_vision")
    @JsonProperty("is_vision")
    private Boolean isVision = false;
    
    @Column(name = "sort_order")
    @JsonProperty("sort_order")
    private Integer sortOrder = 0;
    
    @Column(name = "created_by", length = 100)
    @JsonProperty("created_by")
    private String createdBy;
    
    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

