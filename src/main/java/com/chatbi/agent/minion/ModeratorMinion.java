package com.chatbi.agent.minion;

import com.chatbi.agent.main.Brain;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.types.StreamChunk;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * ModeratorMinion - 路由调度器
 * 
 * 类似于 Python minion 中的 ModeratorMinion，负责：
 * 1. 分析用户查询
 * 2. 选择最合适的 WorkerMinion 类型
 * 3. 创建并执行相应的 Minion
 * 
 * 路由策略：
 * - 简单问答 → NativeMinion
 * - 需要推理 → CotMinion
 * - 需要代码/数据处理 → CodeMinion
 * - 复杂多步任务 → PlanMinion
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ModeratorMinion extends WorkerMinion {
    
    /**
     * 路由提示词模板
     */
    private static final String ROUTE_PROMPT = """
        You are a task router. Based on the user's query, determine the best approach.
        
        Available strategies:
        %s
        
        User query: %s
        
        Respond with only the strategy name (e.g., "code", "cot", "plan", "native").
        """;
    
    /**
     * 关键词映射
     */
    private static final Map<String, List<String>> KEYWORD_PATTERNS = new HashMap<>();
    
    static {
        // 代码/数据分析相关
        KEYWORD_PATTERNS.put("code", Arrays.asList(
                "分析", "analyze", "计算", "calculate", "数据", "data",
                "文件", "file", "excel", "xlsx", "csv", "pdf", "图表", "chart",
                "统计", "statistic", "处理", "process", "执行", "execute",
                "代码", "code", "程序", "program", "脚本", "script"
        ));
        
        // 推理相关
        KEYWORD_PATTERNS.put("cot", Arrays.asList(
                "为什么", "why", "解释", "explain", "推理", "reason",
                "证明", "prove", "分析原因", "逻辑", "logic", "因为", "because"
        ));
        
        // 规划相关
        KEYWORD_PATTERNS.put("plan", Arrays.asList(
                "计划", "plan", "步骤", "step", "流程", "process",
                "如何", "how to", "指南", "guide", "教程", "tutorial",
                "多步", "multi-step", "项目", "project"
        ));
    }
    
    /**
     * 选择的 Minion 类型
     */
    private String selectedMinionType;
    
    /**
     * 实际执行的 Minion
     */
    private WorkerMinion executingMinion;
    
    // ==================== 构造函数 ====================
    
    public ModeratorMinion() {
        super();
    }
    
    public ModeratorMinion(Input input, Brain brain) {
        super(input, brain);
    }
    
    public ModeratorMinion(Input input, Brain brain, Map<String, Object> task) {
        super(input, brain, task);
    }
    
    // ==================== 执行方法 ====================
    
    @Override
    public CompletableFuture<AgentResponse> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 选择合适的 Minion 类型
                String minionType = chooseMinion();
                this.selectedMinionType = minionType;
                
                log.info("Selected minion type: {}", minionType);
                
                // 2. 创建并执行 Minion
                WorkerMinion minion = createMinion(minionType);
                this.executingMinion = minion;
                
                // 3. 执行
                return minion.execute().join();
                
            } catch (Exception e) {
                log.error("Error in ModeratorMinion execution", e);
                return AgentResponse.builder()
                        .success(false)
                        .error(e.getMessage())
                        .build();
            }
        });
    }
    
    @Override
    public void executeStream(Consumer<StreamChunk> chunkConsumer) {
        try {
            // 1. 发送路由决策
            chunkConsumer.accept(StreamChunk.builder()
                    .content("🎯 Analyzing task and selecting best approach...")
                    .chunkType(StreamChunk.ChunkType.THOUGHT)
                    .build());
            
            // 2. 选择 Minion 类型
            String minionType = chooseMinion();
            this.selectedMinionType = minionType;
            
            chunkConsumer.accept(StreamChunk.builder()
                    .content(String.format("📋 Selected strategy: %s", minionType))
                    .chunkType(StreamChunk.ChunkType.LLM_OUTPUT)
                    .build());
            
            // 3. 创建并流式执行 Minion
            WorkerMinion minion = createMinion(minionType);
            this.executingMinion = minion;
            
            chunkConsumer.accept(StreamChunk.builder()
                    .content(String.format("🚀 Executing with %sMinion...", capitalize(minionType)))
                    .chunkType(StreamChunk.ChunkType.STEP_START)
                    .build());
            
            // 4. 执行流式输出
            minion.executeStream(chunkConsumer);
            
        } catch (Exception e) {
            log.error("Error in ModeratorMinion stream execution", e);
            chunkConsumer.accept(StreamChunk.error(e.getMessage()));
        }
    }
    
    // ==================== 路由逻辑 ====================
    
    /**
     * 选择合适的 Minion 类型
     */
    private String chooseMinion() {
        String query = getQuery().toLowerCase();
        
        // 1. 检查是否有上传的文件 - 优先使用 CodeMinion
        if (input != null && query.contains("file:")) {
            log.info("File detected in query, using CodeMinion");
            return "code";
        }
        
        // 2. 基于关键词匹配
        Map<String, Integer> scores = new HashMap<>();
        
        for (Map.Entry<String, List<String>> entry : KEYWORD_PATTERNS.entrySet()) {
            String type = entry.getKey();
            int score = 0;
            
            for (String keyword : entry.getValue()) {
                if (query.contains(keyword.toLowerCase())) {
                    score++;
                }
            }
            
            scores.put(type, score);
        }
        
        // 3. 选择得分最高的类型
        String bestType = "native";
        int bestScore = 0;
        
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestType = entry.getKey();
            }
        }
        
        // 4. 如果没有明确匹配，使用启发式规则
        if (bestScore == 0) {
            // 长查询可能需要更复杂的处理
            if (query.length() > 200) {
                return "plan";
            }
            // 包含问号的可能是问答
            if (query.contains("?") || query.contains("？")) {
                return "cot";
            }
        }
        
        return bestType;
    }
    
    /**
     * 创建 Minion 实例
     */
    private WorkerMinion createMinion(String type) {
        return switch (type.toLowerCase()) {
            case "code" -> new CodeMinion(input, brain, task);
            case "cot" -> new CotMinion(input, brain);
            case "plan" -> new PlanMinion(input, brain);
            default -> new NativeMinion(input, brain);
        };
    }
    
    /**
     * 首字母大写
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    /**
     * 获取路由决策信息
     */
    public Map<String, Object> getRoutingInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("selected_type", selectedMinionType);
        info.put("available_types", MinionRegistry.getRegisteredNames());
        if (executingMinion != null) {
            info.put("executing_minion", executingMinion.getClass().getSimpleName());
        }
        return info;
    }
}

