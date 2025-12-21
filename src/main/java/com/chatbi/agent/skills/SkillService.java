package com.chatbi.agent.skills;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * SkillService - 技能服务
 * 
 * Spring Boot服务，提供技能管理的统一入口。
 * 负责技能的加载、查询和管理。
 */
@Slf4j
@Service
public class SkillService {
    
    /**
     * 项目技能目录
     */
    @Value("${agent.skills.project-path:#{null}}")
    private String projectSkillsPath;
    
    /**
     * 用户技能目录
     */
    @Value("${agent.skills.user-path:#{null}}")
    private String userSkillsPath;
    
    /**
     * 是否自动加载技能
     */
    @Value("${agent.skills.auto-load:true}")
    private boolean autoLoad;
    
    /**
     * 技能加载器
     */
    private SkillLoader skillLoader;
    
    /**
     * 技能注册表
     */
    private SkillRegistry skillRegistry;
    
    // ==================== 初始化 ====================
    
    @PostConstruct
    public void init() {
        log.info("Initializing SkillService...");
        
        // 创建加载器
        Path projectRoot = projectSkillsPath != null 
            ? Paths.get(projectSkillsPath).getParent() 
            : Paths.get(System.getProperty("user.dir"));
        
        skillLoader = new SkillLoader(projectRoot);
        
        // 添加额外搜索路径
        if (projectSkillsPath != null) {
            skillLoader.addSearchPath(projectSkillsPath);
        }
        if (userSkillsPath != null) {
            skillLoader.addSearchPath(userSkillsPath);
        }
        
        // 获取或创建注册表
        skillRegistry = SkillRegistry.getGlobalInstance();
        
        // 自动加载
        if (autoLoad) {
            loadSkills();
        }
        
        log.info("SkillService initialized with {} skills", skillRegistry.size());
    }
    
    // ==================== 加载方法 ====================
    
    /**
     * 加载所有技能
     * 
     * @return 加载的技能数量
     */
    public int loadSkills() {
        int sizeBefore = skillRegistry.size();
        skillLoader.loadAll(skillRegistry);
        int loaded = skillRegistry.size() - sizeBefore;
        log.info("Loaded {} new skills (total: {})", loaded, skillRegistry.size());
        return loaded;
    }
    
    /**
     * 重新加载所有技能
     * 
     * @return 总技能数量
     */
    public int reloadSkills() {
        skillLoader.reload(skillRegistry);
        log.info("Reloaded skills (total: {})", skillRegistry.size());
        return skillRegistry.size();
    }
    
    /**
     * 从指定目录加载技能
     * 
     * @param directory 目录路径
     * @param location 位置类型
     * @return 加载的技能数量
     */
    public int loadFromDirectory(String directory, String location) {
        Path path = Paths.get(directory);
        return skillLoader.loadFromDirectory(path, location, skillRegistry);
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 获取技能
     * 
     * @param name 技能名称
     * @return Skill或null
     */
    public Skill getSkill(String name) {
        return skillRegistry.get(name);
    }
    
    /**
     * 检查技能是否存在
     * 
     * @param name 技能名称
     * @return 是否存在
     */
    public boolean skillExists(String name) {
        return skillRegistry.exists(name);
    }
    
    /**
     * 列出所有技能
     * 
     * @return 技能列表
     */
    public List<Skill> listAllSkills() {
        return skillRegistry.listAll();
    }
    
    /**
     * 按位置列出技能
     * 
     * @param location 位置类型
     * @return 技能列表
     */
    public List<Skill> listSkillsByLocation(String location) {
        return skillRegistry.listByLocation(location);
    }
    
    /**
     * 搜索技能
     * 
     * @param keyword 关键词
     * @return 匹配的技能列表
     */
    public List<Skill> searchSkills(String keyword) {
        return skillRegistry.search(keyword);
    }
    
    /**
     * 获取所有技能名称
     * 
     * @return 名称列表
     */
    public List<String> getSkillNames() {
        return skillRegistry.listAllNames();
    }
    
    // ==================== 执行方法 ====================
    
    /**
     * 执行技能
     * 
     * @param skillName 技能名称
     * @return 执行结果
     */
    public Map<String, Object> executeSkill(String skillName) {
        Skill skill = skillRegistry.get(skillName);
        
        if (skill == null) {
            return Map.of(
                "success", false,
                "error", "Unknown skill: " + skillName,
                "available_skills", getSkillNames()
            );
        }
        
        return Map.of(
            "success", true,
            "skill_name", skill.getName(),
            "skill_description", skill.getDescription(),
            "skill_location", skill.getLocation(),
            "skill_path", skill.getPath() != null ? skill.getPath().toString() : "",
            "prompt", skill.getPrompt(),
            "message", String.format("The \"%s\" skill is loading", skill.getName()),
            "allowed_tools", skill.getAllowedTools()
        );
    }
    
    // ==================== 提示词生成 ====================
    
    /**
     * 生成可用技能提示词
     * 
     * @param charBudget 最大字符数
     * @return 提示词
     */
    public String generateSkillsPrompt(int charBudget) {
        return skillRegistry.generateSkillsPrompt(charBudget);
    }
    
    /**
     * 生成可用技能提示词（默认限制）
     */
    public String generateSkillsPrompt() {
        return skillRegistry.generateSkillsPrompt();
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取统计信息
     * 
     * @return 统计Map
     */
    public Map<String, Object> getStats() {
        return skillRegistry.getStats();
    }
    
    /**
     * 获取技能数量
     */
    public int getSkillCount() {
        return skillRegistry.size();
    }
    
    /**
     * 获取注册表
     */
    public SkillRegistry getRegistry() {
        return skillRegistry;
    }
    
    /**
     * 获取加载器
     */
    public SkillLoader getLoader() {
        return skillLoader;
    }
}

