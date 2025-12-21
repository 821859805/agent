package com.chatbi.agent.skills;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SkillRegistry - 技能注册表
 * 
 * 管理已加载的技能并提供查找功能。
 * 技能按名称组织，可以被查找用于执行。
 * 注册表还处理技能去重（项目技能覆盖用户技能）。
 * 
 * 优先级顺序: project > user > managed
 */
@Slf4j
public class SkillRegistry implements Iterable<Skill> {
    /**
     * 技能存储（按名称索引）
     */
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    
    /**
     * 按位置分类的技能
     */
    private final Map<String, List<Skill>> skillsByLocation = new ConcurrentHashMap<>();
    
    /**
     * 优先级定义
     */
    private static final Map<String, Integer> PRIORITY = Map.of(
        "project", 0,
        "user", 1,
        "managed", 2
    );
    
    public SkillRegistry() {
        // 初始化位置分类
        skillsByLocation.put("project", new ArrayList<>());
        skillsByLocation.put("user", new ArrayList<>());
        skillsByLocation.put("managed", new ArrayList<>());
    }
    
    // ==================== 注册方法 ====================
    
    /**
     * 注册一个技能到注册表
     * 
     * 项目目录技能优先级大于用户目录技能。
     * 如果存在同名的更高优先级技能，新技能不会被注册。
     * 
     * @param skill 要注册的技能
     * @return 如果注册成功返回true，如果被跳过返回false
     */
    public boolean register(Skill skill) {
        if (skill == null || skill.getName() == null) {
            return false;
        }
        
        Skill existing = skills.get(skill.getName());
        
        if (existing != null) {
            // 检查优先级
            int existingPriority = PRIORITY.getOrDefault(existing.getLocation(), 99);
            int newPriority = PRIORITY.getOrDefault(skill.getLocation(), 99);
            
            if (newPriority >= existingPriority) {
                // 跳过 - 现有技能优先级更高或相等
                log.debug("Skipping skill {} - already registered from higher priority location", skill.getName());
                return false;
            }
        }
        
        skills.put(skill.getName(), skill);
        
        // 添加到位置分类
        List<Skill> locationList = skillsByLocation.get(skill.getLocation());
        if (locationList != null) {
            locationList.add(skill);
        }
        
        log.info("Registered skill: {} ({})", skill.getName(), skill.getLocation());
        return true;
    }
    
    /**
     * 批量注册技能
     * 
     * @param skillList 技能列表
     * @return 成功注册的数量
     */
    public int registerAll(List<Skill> skillList) {
        int count = 0;
        for (Skill skill : skillList) {
            if (register(skill)) {
                count++;
            }
        }
        return count;
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 按名称获取技能
     * 
     * @param name 技能名称
     * @return Skill实例，如果未找到返回null
     */
    public Skill get(String name) {
        return skills.get(name);
    }
    
    /**
     * 检查技能是否存在
     * 
     * @param name 技能名称
     * @return 如果技能存在返回true
     */
    public boolean exists(String name) {
        return skills.containsKey(name);
    }
    
    /**
     * 获取所有已注册的技能
     * 
     * @return 所有技能的列表
     */
    public List<Skill> listAll() {
        return new ArrayList<>(skills.values());
    }
    
    /**
     * 按位置类型获取技能
     * 
     * @param location 位置类型（project, user, managed）
     * @return 该位置的技能列表
     */
    public List<Skill> listByLocation(String location) {
        List<Skill> list = skillsByLocation.get(location);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }
    
    /**
     * 获取所有技能名称
     * 
     * @return 技能名称列表
     */
    public List<String> listAllNames() {
        return new ArrayList<>(skills.keySet());
    }
    
    /**
     * 按关键词搜索技能
     * 
     * @param keyword 搜索关键词
     * @return 匹配的技能列表
     */
    public List<Skill> search(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return listAll();
        }
        
        String lowerKeyword = keyword.toLowerCase();
        List<Skill> results = new ArrayList<>();
        
        for (Skill skill : skills.values()) {
            if (skill.getName().toLowerCase().contains(lowerKeyword) ||
                (skill.getDescription() != null && 
                 skill.getDescription().toLowerCase().contains(lowerKeyword))) {
                results.add(skill);
            }
        }
        
        return results;
    }
    
    // ==================== 管理方法 ====================
    
    /**
     * 清空所有已注册的技能
     */
    public void clear() {
        skills.clear();
        for (List<Skill> list : skillsByLocation.values()) {
            list.clear();
        }
        log.info("Cleared all skills from registry");
    }
    
    /**
     * 移除指定技能
     * 
     * @param name 技能名称
     * @return 如果成功移除返回true
     */
    public boolean remove(String name) {
        Skill removed = skills.remove(name);
        if (removed != null) {
            List<Skill> locationList = skillsByLocation.get(removed.getLocation());
            if (locationList != null) {
                locationList.remove(removed);
            }
            log.info("Removed skill: {}", name);
            return true;
        }
        return false;
    }
    
    // ==================== 提示词生成 ====================
    
    /**
     * 生成列举所有可用技能的提示词
     * 
     * @param charBudget 最大字符数限制
     * @return 格式化后的技能提示词
     */
    public String generateSkillsPrompt(int charBudget) {
        List<Skill> skillList = listAll();
        
        if (skillList.isEmpty()) {
            return "";
        }
        
        StringBuilder entries = new StringBuilder();
        int totalChars = 0;
        
        for (Skill skill : skillList) {
            String entry = skill.toXml();
            if (totalChars + entry.length() > charBudget) {
                break;
            }
            entries.append(entry).append("\n");
            totalChars += entry.length();
        }
        
        if (entries.length() == 0) {
            return "";
        }
        
        return String.format("<available_skills>\n%s</available_skills>", entries.toString());
    }
    
    /**
     * 生成默认的技能提示词（10000字符限制）
     */
    public String generateSkillsPrompt() {
        return generateSkillsPrompt(10000);
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取注册表统计信息
     * 
     * @return 统计信息Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSkills", skills.size());
        stats.put("projectSkills", listByLocation("project").size());
        stats.put("userSkills", listByLocation("user").size());
        stats.put("managedSkills", listByLocation("managed").size());
        stats.put("skillNames", listAllNames());
        return stats;
    }
    
    // ==================== 接口实现 ====================
    
    public int size() {
        return skills.size();
    }
    
    public boolean isEmpty() {
        return skills.isEmpty();
    }
    
    public boolean contains(String name) {
        return skills.containsKey(name);
    }
    
    @Override
    public Iterator<Skill> iterator() {
        return skills.values().iterator();
    }
    
    @Override
    public String toString() {
        return String.format("SkillRegistry[skills=%d]", skills.size());
    }
    
    // ==================== 单例支持 ====================
    
    private static volatile SkillRegistry globalInstance;
    
    /**
     * 获取全局技能注册表实例
     * 
     * @return 全局SkillRegistry实例
     */
    public static SkillRegistry getGlobalInstance() {
        if (globalInstance == null) {
            synchronized (SkillRegistry.class) {
                if (globalInstance == null) {
                    globalInstance = new SkillRegistry();
                }
            }
        }
        return globalInstance;
    }
    
    /**
     * 重置全局技能注册表
     */
    public static void resetGlobalInstance() {
        synchronized (SkillRegistry.class) {
            if (globalInstance != null) {
                globalInstance.clear();
            }
            globalInstance = null;
        }
    }
}

