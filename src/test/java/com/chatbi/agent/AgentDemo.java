package com.chatbi.agent;

import com.chatbi.agent.agents.BaseAgent;
import com.chatbi.agent.agents.CodeAgent;
import com.chatbi.agent.chatbi_server_java.service.LlmModelFactoryService;
import com.chatbi.agent.main.Brain;
import com.chatbi.agent.skills.Skill;
import com.chatbi.agent.skills.SkillService;
import com.chatbi.agent.tools.BaseTool;
import com.chatbi.agent.tools.FinalAnswerTool;
import com.chatbi.agent.tools.SkillTool;
import com.chatbi.agent.types.AgentResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Agent Demo - 演示如何使用Agent系统
 * 
 * 这是一个简单的命令行演示，展示如何：
 * 1. 创建和配置Agent
 * 2. 运行任务
 * 3. 处理响应
 * 
 * 类似于Python项目中的gradio_demo.py
 */
@SpringBootTest
@Slf4j
public class AgentDemo implements CommandLineRunner {
    
    @Autowired(required = false)
    private LlmModelFactoryService llmModelFactoryService;
    
    @Autowired(required = false)
    private SkillService skillService;
    
    @Test
    public void run(String... args) throws Exception {
        // 检查是否有demo参数
        boolean runDemo = false;
        for (String arg : args) {
            if ("--demo".equals(arg) || "-d".equals(arg)) {
                runDemo = true;
                break;
            }
        }
        
        if (!runDemo) {
            log.info("Agent Demo available. Run with --demo flag to start interactive mode.");
            return;
        }
        
        log.info("🚀 Starting Agent Demo...");
        
        // 检查LLM服务是否可用
        if (llmModelFactoryService == null) {
            log.warn("⚠️ LlmModelFactoryService not available. Running in mock mode.");
            runMockDemo();
            return;
        }
        
        runInteractiveDemo();
    }
    
    /**
     * 运行交互式演示
     */
    @Test
    public void runInteractiveDemo() {
        log.info("📝 Creating CodeAgent...");
        
        // 创建CodeAgent
        List<BaseTool> tools = new ArrayList<>();
        tools.add(new FinalAnswerTool());
        tools.add(new SkillTool());  // 添加技能工具

        CodeAgent agent = new CodeAgent(llmModelFactoryService,"qwen3-max");
        agent.setTools(tools);
        agent.setName("Demo Agent");
        agent.setMaxSteps(10);
        agent.setEnableReflection(true);
        
        // 初始化Agent
        log.info("⚙️ Setting up agent...");
        agent.setup().join();
        log.info("✅ Agent setup complete!");
        
        // 交互式循环
        Scanner scanner = new Scanner(System.in);
        
        printWelcome();
        
        while (true) {
            System.out.print("\n🤖 You: ");
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                continue;
            }
            
            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                log.info("👋 Goodbye!");
                break;
            }
            
            if ("help".equalsIgnoreCase(input)) {
                printHelp();
                continue;
            }
            
            try {
                log.info("🔄 Processing...");
                Object result = agent.runAsync(input).join();
                
                System.out.println("\n🤖 Agent: " + result);
                
            } catch (Exception e) {
                log.error("❌ Error: " + e.getMessage());
            }
        }
        
        // 清理
        agent.close().join();
        log.info("🧹 Agent cleanup completed");
        scanner.close();
    }
    
    /**
     * 运行模拟演示（无LLM服务时）
     */
    private void runMockDemo() {
        log.info("📝 Running mock demo...");
        
        // 创建模拟Brain和Agent
        Brain mockBrain = new Brain();
        List<BaseTool> tools = new ArrayList<>();
        tools.add(new FinalAnswerTool());
        tools.add(new SkillTool()); // 添加SkillTool
        
        BaseAgent mockAgent = new BaseAgent("Mock Agent", mockBrain, tools);
        
        log.info("✅ Mock agent created with {} tools", tools.size());
        log.info("📌 Available tools:");
        for (BaseTool tool : tools) {
            log.info("   - {}: {}", tool.getName(), tool.getDescription());
        }
        
        // 展示Agent结构
        log.info("\n📊 Agent Structure:");
        log.info("   - Name: {}", mockAgent.getName());
        log.info("   - Tools: {}", mockAgent.getTools().size());
        log.info("   - Max Steps: {}", mockAgent.getMaxSteps());
        
        // 展示已加载的技能
        if (skillService != null) {
            log.info("\n📚 Available Skills:");
            List<Skill> skills = skillService.listAllSkills();
            for (Skill skill : skills) {
                log.info("   - {}: {}", skill.getName(), skill.getDescription());
            }
            log.info("   Total: {} skills", skills.size());
        } else {
            // 使用SkillTool直接加载
            SkillTool skillTool = new SkillTool();
            List<Skill> skills = skillTool.listAvailableSkills();
            log.info("\n📚 Available Skills (loaded via SkillTool):");
            for (Skill skill : skills) {
                log.info("   - {} ({}): {}", skill.getName(), skill.getLocation(), 
                    skill.getDescription().length() > 50 
                        ? skill.getDescription().substring(0, 50) + "..." 
                        : skill.getDescription());
            }
            log.info("   Total: {} skills", skills.size());
        }
        
        log.info("\n💡 To run the full demo, configure LLM service and run with --demo flag.");
    }
    
    /**
     * 打印欢迎信息
     */
    private void printWelcome() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           🤖 Minion Agent Demo (Java Edition)          ║");
        System.out.println("╠════════════════════════════════════════════════════════╣");
        System.out.println("║  This demo uses the CodeAgent with code-based thinking ║");
        System.out.println("║  You can ask the agent to:                             ║");
        System.out.println("║    - Solve math problems                               ║");
        System.out.println("║    - Answer questions with reasoning                   ║");
        System.out.println("║    - Analyze data                                      ║");
        System.out.println("║    - And much more!                                    ║");
        System.out.println("╠════════════════════════════════════════════════════════╣");
        System.out.println("║  Commands:                                             ║");
        System.out.println("║    help  - Show this help message                      ║");
        System.out.println("║    exit  - Exit the demo                               ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
    }
    
    /**
     * 打印帮助信息
     */
    private void printHelp() {
        System.out.println();
        System.out.println("📖 Help:");
        System.out.println("   - Type your question or task and press Enter");
        System.out.println("   - The agent will think step by step and provide an answer");
        System.out.println("   - Type 'exit' or 'quit' to stop the demo");
        System.out.println();
        System.out.println("📌 Example queries:");
        System.out.println("   - What is 234 * 568?");
        System.out.println("   - Calculate the sum of numbers from 1 to 100");
        System.out.println("   - What is the factorial of 10?");
        System.out.println("   - Explain how to solve a quadratic equation");
        System.out.println();
    }
    
    /**
     * 程序化运行Agent（供测试使用）
     */
    public static AgentResponse runAgent(LlmModelFactoryService llmService, String task) {
        CodeAgent agent = new CodeAgent(llmService,"qwen3-max");
        agent.setName("Programmatic Agent");
        agent.setMaxSteps(10);
        
        agent.setup().join();
        
        try {
            Object result = agent.runAsync(task).join();
            
            if (result instanceof AgentResponse) {
                return (AgentResponse) result;
            }
            
            AgentResponse response = new AgentResponse();
            response.setContent(result.toString());
            response.setAnswer(result.toString());
            return response;
            
        } finally {
            agent.close().join();
        }
    }
}

