package com.chatbi.agent.skills;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill数据类 - 表示一个加载的技能及其元数据和内容
 * 
 * 技能是模块化的包，通过提供专门的知识、工作流和工具来扩展Agent的能力。
 * 每个技能由一个SKILL.md文件定义，包含YAML frontmatter和markdown内容。
 */
@Slf4j
@Data
public class Skill {
    /**
     * 技能名称
     */
    private String name;
    
    /**
     * 技能描述
     */
    private String description;
    
    /**
     * 技能内容（markdown指令）
     */
    private String content;
    
    /**
     * 技能目录路径
     */
    private Path path;
    
    /**
     * 许可证信息
     */
    private String license;
    
    /**
     * 允许使用的工具列表
     */
    private List<String> allowedTools = new ArrayList<>();
    
    /**
     * 额外元数据
     */
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 来源位置类型: project, user, managed
     */
    private String location = "project";
    
    // ==================== 构造函数 ====================
    
    public Skill() {}
    
    public Skill(String name, String description, String content, Path path) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.path = path;
    }
    
    // ==================== 静态工厂方法 ====================
    
    /**
     * 从SKILL.md文件创建Skill实例
     * 
     * @param skillMdPath SKILL.md文件路径
     * @param location 技能来源位置
     * @return Skill实例，如果解析失败返回null
     */
    public static Skill fromSkillMd(Path skillMdPath, String location) {
        if (!Files.exists(skillMdPath)) {
            return null;
        }
        
        try {
            String content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
            ParsedContent parsed = parseFrontmatter(content);
            
            if (parsed.frontmatter == null || parsed.frontmatter.isEmpty()) {
                log.warn("No frontmatter found in: {}", skillMdPath);
                return null;
            }
            
            String name = (String) parsed.frontmatter.get("name");
            String description = (String) parsed.frontmatter.get("description");
            
            if (name == null || name.isEmpty() || description == null || description.isEmpty()) {
                log.warn("Missing name or description in: {}", skillMdPath);
                return null;
            }
            
            Skill skill = new Skill();
            skill.setName(name);
            skill.setDescription(description);
            skill.setContent(parsed.body.trim());
            skill.setPath(skillMdPath.getParent());
            skill.setLicense((String) parsed.frontmatter.get("license"));
            skill.setLocation(location);
            
            // 解析allowed-tools
            Object allowedToolsObj = parsed.frontmatter.get("allowed-tools");
            if (allowedToolsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) allowedToolsObj;
                skill.setAllowedTools(tools);
            }
            
            // 解析metadata
            Object metadataObj = parsed.frontmatter.get("metadata");
            if (metadataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) metadataObj;
                skill.setMetadata(meta);
            }
            
            return skill;
            
        } catch (IOException e) {
            log.error("Error reading skill file: {}", skillMdPath, e);
            return null;
        } catch (Exception e) {
            log.error("Error parsing skill file: {}", skillMdPath, e);
            return null;
        }
    }
    
    // ==================== 解析方法 ====================
    
    /**
     * 解析后的内容
     */
    private static class ParsedContent {
        Map<String, Object> frontmatter;
        String body;
        
        ParsedContent(Map<String, Object> frontmatter, String body) {
            this.frontmatter = frontmatter;
            this.body = body;
        }
    }
    
    /**
     * 解析YAML frontmatter
     * 
     * @param content 原始markdown内容
     * @return 解析后的frontmatter和body
     */
    private static ParsedContent parseFrontmatter(String content) {
        // 匹配YAML frontmatter: 以---开头，以---结尾
        Pattern pattern = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        
        if (!matcher.matches()) {
            return new ParsedContent(new HashMap<>(), content);
        }
        
        String yamlContent = matcher.group(1);
        String body = matcher.group(2);
        
        try {
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> frontmatter = yaml.load(yamlContent);
            if (frontmatter == null) {
                frontmatter = new HashMap<>();
            }
            return new ParsedContent(frontmatter, body);
        } catch (Exception e) {
            log.warn("Failed to parse YAML frontmatter: {}", e.getMessage());
            return new ParsedContent(new HashMap<>(), content);
        }
    }
    
    // ==================== 实例方法 ====================
    
    /**
     * 获取完整的提示词内容
     * 包含技能位置头部用于解析相对路径
     * 
     * @return 完整的提示词字符串
     */
    public String getPrompt() {
        String header = String.format("Loading: %s\nBase directory: %s\n\n", name, path);
        return header + content;
    }
    
    /**
     * 格式化为XML用于包含在提示词中
     * 
     * @return XML格式的技能条目
     */
    public String toXml() {
        return String.format("""
            <skill>
            <name>%s</name>
            <description>%s</description>
            <location>%s</location>
            </skill>""", name, escapeXml(description), location);
    }
    
    /**
     * 转义XML特殊字符
     */
    private String escapeXml(String input) {
        if (input == null) {
            return "";
        }
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
    
    /**
     * 获取技能摘要信息
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("name", name);
        summary.put("description", description);
        summary.put("location", location);
        summary.put("path", path != null ? path.toString() : null);
        summary.put("allowedTools", allowedTools);
        return summary;
    }
    
    @Override
    public String toString() {
        return String.format("Skill(name=%s, location=%s)", name, location);
    }
}

