package com.chatbi.agent.minion;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * MinionRegistry - Minion 注册表
 * 
 * 类似于 Python minion 中的 WORKER_MINIONS 注册表，
 * 管理不同类型的 Minion 实现
 */
@Slf4j
public class MinionRegistry {
    
    private static final Map<String, Class<? extends WorkerMinion>> REGISTRY = new HashMap<>();
    
    static {
        // 注册默认的 Minion 类型
        register("native", NativeMinion.class);
        register("cot", CotMinion.class);
        register("code", CodeMinion.class);
        register("plan", PlanMinion.class);
    }
    
    /**
     * 注册 Minion 类型
     */
    public static void register(String name, Class<? extends WorkerMinion> minionClass) {
        REGISTRY.put(name.toLowerCase(), minionClass);
        log.info("Registered Minion: {} -> {}", name, minionClass.getSimpleName());
    }
    
    /**
     * 获取 Minion 类
     */
    public static Class<? extends WorkerMinion> get(String name) {
        return REGISTRY.get(name.toLowerCase());
    }
    
    /**
     * 检查是否存在
     */
    public static boolean exists(String name) {
        return REGISTRY.containsKey(name.toLowerCase());
    }
    
    /**
     * 获取所有注册的名称
     */
    public static Set<String> getRegisteredNames() {
        return REGISTRY.keySet();
    }
    
    /**
     * 获取注册表描述（用于 LLM 选择）
     */
    public static String getRegistryDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available Minion types:\n");
        
        for (Map.Entry<String, Class<? extends WorkerMinion>> entry : REGISTRY.entrySet()) {
            sb.append(String.format("- %s: %s\n", entry.getKey(), getDescription(entry.getKey())));
        }
        
        return sb.toString();
    }
    
    /**
     * 获取 Minion 类型描述
     */
    private static String getDescription(String name) {
        return switch (name.toLowerCase()) {
            case "native" -> "Direct LLM response, suitable for simple questions";
            case "cot" -> "Chain-of-Thought reasoning, suitable for complex reasoning tasks";
            case "code" -> "Code execution with Thought→Code→Observation loop, suitable for data analysis and calculations";
            case "plan" -> "Planning and task decomposition, suitable for multi-step tasks";
            default -> "Unknown minion type";
        };
    }
}

