package com.chatbi.agent.controller;

import com.chatbi.agent.skills.Skill;
import com.chatbi.agent.skills.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SkillController - 技能REST API控制器
 * 
 * 提供技能相关的HTTP API接口：
 * - GET /api/skills - 列出所有技能
 * - GET /api/skills/{name} - 获取技能详情
 * - POST /api/skills/{name}/execute - 执行技能
 * - POST /api/skills/reload - 重新加载技能
 * - GET /api/skills/stats - 获取统计信息
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*")
public class SkillController {
    
    @Autowired
    private SkillService skillService;
    
    // ==================== 查询接口 ====================
    
    /**
     * 列出所有技能
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String keyword) {
        
        List<Skill> skills;
        
        if (keyword != null && !keyword.isEmpty()) {
            skills = skillService.searchSkills(keyword);
        } else if (location != null && !location.isEmpty()) {
            skills = skillService.listSkillsByLocation(location);
        } else {
            skills = skillService.listAllSkills();
        }
        
        List<Map<String, Object>> skillList = skills.stream()
            .map(Skill::getSummary)
            .collect(Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", skills.size());
        response.put("skills", skillList);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取技能详情
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String name) {
        Skill skill = skillService.getSkill(name);
        
        if (skill == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Skill not found: " + name);
            error.put("available_skills", skillService.getSkillNames());
            return ResponseEntity.status(404).body(error);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("skill", skill.getSummary());
        response.put("content", skill.getContent());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取技能名称列表
     */
    @GetMapping("/names")
    public ResponseEntity<Map<String, Object>> getSkillNames() {
        List<String> names = skillService.getSkillNames();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", names.size());
        response.put("names", names);
        
        return ResponseEntity.ok(response);
    }
    
    // ==================== 执行接口 ====================
    
    /**
     * 执行技能
     */
    @PostMapping("/{name}/execute")
    public ResponseEntity<Map<String, Object>> executeSkill(@PathVariable String name) {
        log.info("Executing skill: {}", name);
        
        Map<String, Object> result = skillService.executeSkill(name);
        
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.status(404).body(result);
        }
    }
    
    /**
     * 获取技能提示词
     */
    @GetMapping("/{name}/prompt")
    public ResponseEntity<Map<String, Object>> getSkillPrompt(@PathVariable String name) {
        Skill skill = skillService.getSkill(name);
        
        if (skill == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Skill not found: " + name);
            return ResponseEntity.status(404).body(error);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("skill_name", skill.getName());
        response.put("prompt", skill.getPrompt());
        
        return ResponseEntity.ok(response);
    }
    
    // ==================== 管理接口 ====================
    
    /**
     * 重新加载技能
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadSkills() {
        log.info("Reloading skills...");
        
        int count = skillService.reloadSkills();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Skills reloaded successfully");
        response.put("total_skills", count);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = skillService.getStats();
        stats.put("success", true);
        return ResponseEntity.ok(stats);
    }
    
    /**
     * 生成可用技能提示词
     */
    @GetMapping("/prompt")
    public ResponseEntity<Map<String, Object>> getAvailableSkillsPrompt(
            @RequestParam(defaultValue = "10000") int charBudget) {
        
        String prompt = skillService.generateSkillsPrompt(charBudget);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("prompt", prompt);
        response.put("char_count", prompt.length());
        
        return ResponseEntity.ok(response);
    }
    
    // ==================== 健康检查 ====================
    
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("skill_count", skillService.getSkillCount());
        return ResponseEntity.ok(health);
    }
}

