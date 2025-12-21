package com.chatbi.agent.skills;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * SkillLoader - 技能加载器
 * 
 * 从标准目录中发现并加载技能。
 * 
 * 搜索路径（按优先级顺序）：
 * 1. .claude/skills (项目级)
 * 2. .minion/skills (项目级)
 * 3. ~/.claude/skills (用户级)
 * 4. ~/.minion/skills (用户级)
 * 
 * 项目级技能覆盖同名的用户级技能。
 */
@Slf4j
public class SkillLoader {
    
    /**
     * 默认技能目录名称
     */
    private static final List<String> SKILL_DIRS = Arrays.asList(
        ".claude/skills",
        ".minion/skills",
        // 项目内置技能目录
        "src/main/java/com/chatbi/agent/skills/skills"
    );
    
    /**
     * 技能文件名
     */
    private static final String SKILL_FILE = "SKILL.md";
    
    /**
     * 项目根目录
     */
    private final Path projectRoot;
    
    /**
     * 用户主目录
     */
    private final Path homeDir;
    
    /**
     * 额外的技能搜索路径
     */
    private final List<Path> additionalPaths = new ArrayList<>();
    
    // ==================== 构造函数 ====================
    
    public SkillLoader() {
        this(Paths.get(System.getProperty("user.dir")));
    }
    
    public SkillLoader(Path projectRoot) {
        this.projectRoot = projectRoot != null ? projectRoot : Paths.get(System.getProperty("user.dir"));
        this.homeDir = Paths.get(System.getProperty("user.home"));
    }
    
    // ==================== 配置方法 ====================
    
    /**
     * 添加额外的技能搜索路径
     * 
     * @param path 搜索路径
     * @return this（用于链式调用）
     */
    public SkillLoader addSearchPath(Path path) {
        if (path != null) {
            additionalPaths.add(path);
        }
        return this;
    }
    
    /**
     * 添加额外的技能搜索路径
     * 
     * @param path 搜索路径字符串
     * @return this
     */
    public SkillLoader addSearchPath(String path) {
        if (path != null && !path.isEmpty()) {
            additionalPaths.add(Paths.get(path));
        }
        return this;
    }
    
    // ==================== 搜索路径 ====================
    
    /**
     * 获取所有搜索路径
     * 
     * @return (路径, 位置类型) 元组列表
     */
    public List<SearchPath> getSearchPaths() {
        List<SearchPath> paths = new ArrayList<>();
        
        // 项目级路径（更高优先级）
        for (String skillDir : SKILL_DIRS) {
            Path projectPath = projectRoot.resolve(skillDir);
            paths.add(new SearchPath(projectPath, "project"));
        }
        
        // 用户级路径（较低优先级）
        for (String skillDir : SKILL_DIRS) {
            Path userPath = homeDir.resolve(skillDir);
            paths.add(new SearchPath(userPath, "user"));
        }
        
        // 额外路径
        for (Path additionalPath : additionalPaths) {
            paths.add(new SearchPath(additionalPath, "managed"));
        }
        
        return paths;
    }
    
    /**
     * 搜索路径记录
     */
    public record SearchPath(Path path, String location) {}
    
    // ==================== 发现技能 ====================
    
    /**
     * 在技能目录中发现所有技能
     * 
     * @param skillsDir 包含技能子目录的目录
     * @return SKILL.md文件路径列表
     */
    public List<Path> discoverSkills(Path skillsDir) {
        List<Path> skillFiles = new ArrayList<>();
        
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return skillFiles;
        }
        
        try (Stream<Path> items = Files.list(skillsDir)) {
            items.filter(Files::isDirectory).forEach(item -> {
                // 检查目录中是否有SKILL.md
                Path skillMd = item.resolve(SKILL_FILE);
                
                if (Files.exists(skillMd)) {
                    skillFiles.add(skillMd);
                } else {
                    // 检查嵌套子目录
                    try (Stream<Path> nestedItems = Files.list(item)) {
                        nestedItems.filter(Files::isDirectory).forEach(nestedItem -> {
                            Path nestedSkillMd = nestedItem.resolve(SKILL_FILE);
                            if (Files.exists(nestedSkillMd)) {
                                skillFiles.add(nestedSkillMd);
                            }
                        });
                    } catch (IOException e) {
                        log.debug("Error listing nested directory: {}", item, e);
                    }
                }
            });
        } catch (IOException e) {
            log.debug("Error listing skills directory: {}", skillsDir, e);
        }
        
        return skillFiles;
    }
    
    // ==================== 加载技能 ====================
    
    /**
     * 从SKILL.md文件加载单个技能
     * 
     * @param skillMdPath SKILL.md文件路径
     * @param location 位置类型
     * @return Skill实例，加载失败返回null
     */
    public Skill loadSkill(Path skillMdPath, String location) {
        try {
            Skill skill = Skill.fromSkillMd(skillMdPath, location);
            if (skill != null) {
                log.debug("Loaded skill: {} from {}", skill.getName(), skillMdPath);
            } else {
                log.warn("Failed to parse skill: {}", skillMdPath);
            }
            return skill;
        } catch (Exception e) {
            log.error("Error loading skill from {}: {}", skillMdPath, e.getMessage());
            return null;
        }
    }
    
    /**
     * 加载所有搜索路径中的技能
     * 
     * @return 包含所有技能的SkillRegistry
     */
    public SkillRegistry loadAll() {
        return loadAll(null);
    }
    
    /**
     * 加载所有搜索路径中的技能到指定注册表
     * 
     * @param registry 目标注册表，为null时使用全局实例
     * @return 包含所有技能的SkillRegistry
     */
    public SkillRegistry loadAll(SkillRegistry registry) {
        if (registry == null) {
            registry = SkillRegistry.getGlobalInstance();
        }
        
        int totalLoaded = 0;
        
        for (SearchPath searchPath : getSearchPaths()) {
            List<Path> skillFiles = discoverSkills(searchPath.path());
            
            for (Path skillMdPath : skillFiles) {
                Skill skill = loadSkill(skillMdPath, searchPath.location());
                if (skill != null) {
                    boolean registered = registry.register(skill);
                    if (registered) {
                        totalLoaded++;
                    }
                }
            }
        }
        
        log.info("Loaded {} skills from all search paths", totalLoaded);
        return registry;
    }
    
    /**
     * 重新加载所有技能（先清空现有技能）
     * 
     * @return 重新加载后的SkillRegistry
     */
    public SkillRegistry reload() {
        return reload(null);
    }
    
    /**
     * 重新加载所有技能到指定注册表
     * 
     * @param registry 目标注册表
     * @return 重新加载后的SkillRegistry
     */
    public SkillRegistry reload(SkillRegistry registry) {
        if (registry == null) {
            registry = SkillRegistry.getGlobalInstance();
        }
        
        registry.clear();
        return loadAll(registry);
    }
    
    /**
     * 从指定目录加载技能
     * 
     * @param skillsDir 技能目录
     * @param location 位置类型
     * @param registry 目标注册表
     * @return 加载的技能数量
     */
    public int loadFromDirectory(Path skillsDir, String location, SkillRegistry registry) {
        if (registry == null) {
            registry = SkillRegistry.getGlobalInstance();
        }
        
        List<Path> skillFiles = discoverSkills(skillsDir);
        int count = 0;
        
        for (Path skillMdPath : skillFiles) {
            Skill skill = loadSkill(skillMdPath, location);
            if (skill != null && registry.register(skill)) {
                count++;
            }
        }
        
        log.info("Loaded {} skills from {}", count, skillsDir);
        return count;
    }
    
    // ==================== 便捷方法 ====================
    
    /**
     * 便捷函数：加载所有技能
     * 
     * @return SkillRegistry
     */
    public static SkillRegistry loadSkills() {
        return loadSkills(null);
    }
    
    /**
     * 便捷函数：从指定项目根目录加载所有技能
     * 
     * @param projectRoot 项目根目录
     * @return SkillRegistry
     */
    public static SkillRegistry loadSkills(Path projectRoot) {
        SkillLoader loader = new SkillLoader(projectRoot);
        return loader.loadAll();
    }
    
    /**
     * 获取所有可用技能列表
     * 
     * @return 技能列表
     */
    public static List<Skill> getAvailableSkills() {
        SkillRegistry registry = SkillRegistry.getGlobalInstance();
        
        if (registry.isEmpty()) {
            loadSkills();
        }
        
        return registry.listAll();
    }
    
    @Override
    public String toString() {
        return String.format("SkillLoader[projectRoot=%s, searchPaths=%d]", 
            projectRoot, getSearchPaths().size());
    }
}

