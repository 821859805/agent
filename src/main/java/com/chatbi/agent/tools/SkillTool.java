package com.chatbi.agent.tools;

import com.chatbi.agent.executor.PythonExecutor;
import com.chatbi.agent.skills.Skill;
import com.chatbi.agent.skills.SkillLoader;
import com.chatbi.agent.skills.SkillRegistry;
import com.chatbi.agent.types.StreamChunk;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * SkillTool - 技能执行工具
 * 
 * 该工具允许Agent调用技能，技能提供专门的知识和工作流用于特定任务。
 * 当技能被调用时，其指令会被加载到对话上下文中。
 * 
 * 技能是指令、脚本和资源的文件夹，Agent可以动态加载以提高专门任务的性能。
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillTool extends BaseTool {
    
    private static final String TOOL_NAME = "Skill";
    private static final String TOOL_DESCRIPTION = """
        Execute a skill within the main conversation.
        
        Skills are folders of instructions, scripts, and resources that Claude loads
        dynamically to improve performance on specialized tasks.
        
        Usage:
        - Invoke skills using this tool with the skill name only (no arguments)
        - When you invoke a skill, its prompt will expand and provide detailed instructions
        - Only use skills listed in <available_skills> in the system prompt
        
        Important:
        - Only use skills that are listed as available
        - Do not invoke a skill that is already running
        """;
    
    /**
     * 技能注册表（延迟加载）
     */
    private SkillRegistry registry;
    
    /**
     * 是否已初始化
     */
    private boolean initialized = false;
    
    /**
     * Python 执行器（用于执行 Skill 脚本）
     */
    private PythonExecutor pythonExecutor;
    
    /**
     * 流式输出回调（可选）
     */
    private Consumer<StreamChunk> streamCallback;
    
    // ==================== 构造函数 ====================
    
    public SkillTool() {
        super();
        this.name = TOOL_NAME;
        this.description = TOOL_DESCRIPTION;
        this.outputType = "object";
        
        // 设置输入参数
        Map<String, Object> skillInput = new HashMap<>();
        skillInput.put("type", "string");
        skillInput.put("description", "The skill name to execute (e.g., 'xlsx', 'docx')");
        
        this.inputs = new HashMap<>();
        this.inputs.put("skill", skillInput);
    }
    
    public SkillTool(SkillRegistry registry) {
        this();
        this.registry = registry;
        this.initialized = (registry != null);
    }
    
    // ==================== 初始化 ====================
    
    /**
     * 确保技能注册表已初始化
     */
    private void ensureInitialized() {
        if (!initialized || registry == null) {
            synchronized (this) {
                if (!initialized || registry == null) {
                    registry = SkillLoader.loadSkills();
                    initialized = true;
                    log.info("SkillTool initialized with {} skills", registry.size());
                }
            }
        }
    }
    
    /**
     * 获取技能注册表
     */
    public SkillRegistry getRegistry() {
        ensureInitialized();
        return registry;
    }
    
    // ==================== 工具执行 ====================
    
    @Override
    protected Object forward(Object... args) {
        if (args.length > 0 && args[0] instanceof String) {
            return executeSkill((String) args[0]);
        }
        return Map.of("success", false, "error", "Skill name is required");
    }
    
    @Override
    protected Object forwardWithMap(Map<String, Object> kwargs) {
        String skillName = (String) kwargs.get("skill");
        return executeSkill(skillName);
    }
    
    /**
     * 执行技能
     * 
     * @param skillName 技能名称
     * @return 包含技能提示词和元数据的Map
     */
    public Map<String, Object> executeSkill(String skillName) {
        ensureInitialized();
        
        // 检查技能是否存在
        Skill skill = registry.get(skillName);
        
        if (skill == null) {
            List<String> available = registry.listAllNames();
            List<String> showAvailable = available.size() > 10 
                ? available.subList(0, 10) 
                : available;
            
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", "Unknown skill: " + skillName);
            errorResult.put("available_skills", showAvailable);
            errorResult.put("hint", "Use one of the available skills listed above");
            
            log.warn("Skill not found: {}. Available: {}", skillName, showAvailable);
            return errorResult;
        }
        
        // 获取技能提示词
        String prompt = skill.getPrompt();
        
        // 构建响应
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("skill_name", skill.getName());
        result.put("skill_description", skill.getDescription());
        result.put("skill_location", skill.getLocation());
        result.put("skill_path", skill.getPath() != null ? skill.getPath().toString() : null);
        result.put("prompt", prompt);
        result.put("message", String.format("The \"%s\" skill is loading", skill.getName()));
        result.put("allowed_tools", skill.getAllowedTools());
        
        log.info("Executed skill: {} from {}", skill.getName(), skill.getLocation());
        return result;
    }
    
    // ==================== 验证方法 ====================
    
    /**
     * 验证技能是否存在且可执行
     * 
     * @param skillName 技能名称
     * @return ValidationResult
     */
    public ValidationResult validateSkill(String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return new ValidationResult(false, "Skill name is required");
        }
        
        ensureInitialized();
        
        if (!registry.exists(skillName)) {
            List<String> available = registry.listAllNames();
            String availableStr = String.join(", ", 
                available.size() > 5 ? available.subList(0, 5) : available);
            return new ValidationResult(false, 
                String.format("Unknown skill: %s. Available: %s", skillName, availableStr));
        }
        
        return new ValidationResult(true, null);
    }
    
    /**
     * 验证结果
     */
    public record ValidationResult(boolean valid, String errorMessage) {}
    
    // ==================== 提示词生成 ====================
    
    /**
     * 生成可用技能提示词
     * 
     * @param charBudget 最大字符数
     * @return 格式化的技能提示词
     */
    public String getAvailableSkillsPrompt(int charBudget) {
        ensureInitialized();
        return registry.generateSkillsPrompt(charBudget);
    }
    
    /**
     * 生成可用技能提示词（默认10000字符限制）
     */
    public String getAvailableSkillsPrompt() {
        return getAvailableSkillsPrompt(10000);
    }
    
    /**
     * 生成完整的技能工具提示词
     * 
     * @return 完整的技能工具提示词
     */
    public String generateSkillToolPrompt() {
        ensureInitialized();
        
        List<Skill> skills = registry.listAll();
        
        if (skills.isEmpty()) {
            return """
                Execute a skill within the main conversation.
                
                No skills are currently available. Skills can be added to:
                - .claude/skills/ (project-level)
                - ~/.claude/skills/ (user-level)
                - .minion/skills/ (project-level)
                - ~/.minion/skills/ (user-level)
                """;
        }
        
        StringBuilder skillsXml = new StringBuilder();
        for (Skill skill : skills) {
            skillsXml.append(skill.toXml()).append("\n");
        }
        
        return String.format("""
            Execute a skill within the main conversation.
            
            <skills_instructions>
            When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.
            
            How to use skills:
            - Invoke skills using this tool with the skill name only (no arguments)
            - When you invoke a skill, you will see <command-message>The "{name}" skill is loading</command-message>
            - The skill's prompt will expand and provide detailed instructions on how to complete the task
            - Base directory provided in output for resolving bundled resources (references/, scripts/, assets/)
            
            Important:
            - Only use skills listed in <available_skills> below
            - Do not invoke a skill that is already running
            </skills_instructions>
            
            <available_skills>
            %s</available_skills>
            """, skillsXml.toString());
    }
    
    // ==================== 脚本执行方法 ====================
    
    /**
     * 执行 Skill 中的脚本
     * 
     * 类似于 Python minion 中 Skill 的脚本执行能力
     * 
     * @param skillName 技能名称
     * @param scriptName 脚本名称（如 "document.py"）
     * @param args 脚本参数
     * @return 执行结果
     */
    public Map<String, Object> executeSkillScript(String skillName, String scriptName, Map<String, Object> args) {
        ensureInitialized();
        
        Skill skill = registry.get(skillName);
        if (skill == null) {
            return Map.of("success", false, "error", "Skill not found: " + skillName);
        }
        
        // 查找脚本文件
        Path skillPath = skill.getPath();
        Path scriptsDir = skillPath.resolve("scripts");
        Path scriptFile = scriptsDir.resolve(scriptName);
        
        if (!Files.exists(scriptFile)) {
            return Map.of("success", false, "error", "Script not found: " + scriptName);
        }
        
        // 发送流式事件
        if (streamCallback != null) {
            streamCallback.accept(StreamChunk.builder()
                    .content(String.format("Executing script: %s/%s", skillName, scriptName))
                    .chunkType(StreamChunk.ChunkType.SKILL_LOADING)
                    .toolName(skillName)
                    .build());
        }
        
        try {
            // 读取脚本内容
            String scriptContent = Files.readString(scriptFile);
            
            // 初始化 Python 执行器（如果需要）
            if (pythonExecutor == null) {
                pythonExecutor = new PythonExecutor();
            }
            
            // 设置工作目录为 Skill 目录
            pythonExecutor.setWorkDir(skillPath);
            
            // 注入参数
            if (args != null) {
                pythonExecutor.sendVariables(args);
            }
            
            // 执行脚本
            PythonExecutor.ExecutionResult result = pythonExecutor.execute(scriptContent);
            
            // 发送执行结果
            if (streamCallback != null) {
                if (result.isSuccess()) {
                    streamCallback.accept(StreamChunk.observation(result.getOutput(), true));
                } else {
                    streamCallback.accept(StreamChunk.error(result.getError()));
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("output", result.getOutput());
            response.put("skill_name", skillName);
            response.put("script_name", scriptName);
            
            if (!result.isSuccess()) {
                response.put("error", result.getError());
            }
            
            if (result.isFinalAnswer()) {
                response.put("final_answer", result.getFinalAnswerValue());
            }
            
            return response;
            
        } catch (IOException e) {
            log.error("Error reading script: {}", scriptFile, e);
            return Map.of("success", false, "error", "Error reading script: " + e.getMessage());
        }
    }
    
    /**
     * 列出 Skill 中的可用脚本
     */
    public List<String> listSkillScripts(String skillName) {
        ensureInitialized();
        
        Skill skill = registry.get(skillName);
        if (skill == null) {
            return Collections.emptyList();
        }
        
        Path scriptsDir = skill.getPath().resolve("scripts");
        if (!Files.exists(scriptsDir)) {
            return Collections.emptyList();
        }
        
        try {
            return Files.list(scriptsDir)
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".py") || name.endsWith(".js"))
                    .toList();
        } catch (IOException e) {
            log.error("Error listing scripts", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 设置流式输出回调
     */
    public void setStreamCallback(Consumer<StreamChunk> callback) {
        this.streamCallback = callback;
    }
    
    /**
     * 执行 Skill 并注入 prompt 到上下文
     * 
     * 增强版执行，类似于 Python minion 的 Skill 加载过程
     * 
     * @param skillName 技能名称
     * @param context 上下文参数（如文件路径等）
     * @return 执行结果，包含注入的 prompt
     */
    public Map<String, Object> executeSkillWithContext(String skillName, Map<String, Object> context) {
        Map<String, Object> result = executeSkill(skillName);
        
        if (Boolean.TRUE.equals(result.get("success"))) {
            // 增强 prompt，注入上下文信息
            String prompt = (String) result.get("prompt");
            if (prompt != null && context != null) {
                StringBuilder enhancedPrompt = new StringBuilder(prompt);
                enhancedPrompt.append("\n\n## Context\n");
                
                for (Map.Entry<String, Object> entry : context.entrySet()) {
                    enhancedPrompt.append(String.format("- %s: %s\n", entry.getKey(), entry.getValue()));
                }
                
                result.put("prompt", enhancedPrompt.toString());
            }
            
            // 检查是否有相关脚本
            List<String> scripts = listSkillScripts(skillName);
            if (!scripts.isEmpty()) {
                result.put("available_scripts", scripts);
                result.put("can_execute_scripts", true);
            }
        }
        
        return result;
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 列出所有可用技能
     */
    public List<Skill> listAvailableSkills() {
        ensureInitialized();
        return registry.listAll();
    }
    
    /**
     * 获取技能信息
     */
    public Map<String, Object> getSkillInfo(String skillName) {
        ensureInitialized();
        Skill skill = registry.get(skillName);
        if (skill != null) {
            return skill.getSummary();
        }
        return null;
    }
    
    /**
     * 搜索技能
     */
    public List<Skill> searchSkills(String keyword) {
        ensureInitialized();
        return registry.search(keyword);
    }
    
    // ==================== 管理方法 ====================
    
    /**
     * 重新加载技能
     */
    public void reloadSkills() {
        synchronized (this) {
            SkillLoader loader = new SkillLoader();
            registry = loader.reload();
            initialized = true;
            log.info("Reloaded {} skills", registry.size());
        }
    }
    
    /**
     * 获取技能统计信息
     */
    public Map<String, Object> getStats() {
        ensureInitialized();
        return registry.getStats();
    }
    
    @Override
    public String toString() {
        ensureInitialized();
        return String.format("SkillTool[skills=%d]", registry.size());
    }
}

