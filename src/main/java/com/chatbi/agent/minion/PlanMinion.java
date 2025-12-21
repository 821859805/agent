package com.chatbi.agent.minion;

import com.chatbi.agent.main.Brain;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.types.StreamChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * PlanMinion - 计划 Minion
 * 
 * 用于复杂任务的分解和规划，将大任务分解为子任务。
 * 类似于 Python minion 中的 PlanMinion。
 */
@Slf4j
public class PlanMinion extends WorkerMinion {
    
    /**
     * 计划提示词
     */
    private static final String PLAN_PROMPT = """
        You are a task planning expert. Your job is to break down complex tasks into smaller, manageable steps.
        
        For the given task, create a detailed plan with numbered steps.
        Each step should be:
        1. Clear and actionable
        2. Small enough to complete in one action
        3. Logically ordered
        
        Task: %s
        
        Please provide your plan in the following format:
        
        ## Plan
        1. [First step]
        2. [Second step]
        3. [Third step]
        ...
        
        ## Execution Strategy
        [Describe how to execute the plan]
        """;
    
    /**
     * 子任务列表
     */
    private List<Map<String, Object>> subtasks = new ArrayList<>();
    
    public PlanMinion() {
        super();
    }
    
    public PlanMinion(Input input, Brain brain) {
        super(input, brain);
    }
    
    @Override
    public CompletableFuture<AgentResponse> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String query = getQuery();
                
                // 生成计划
                List<String> plan = generatePlan(query);
                
                // 执行子任务（使用其他 Minion）
                StringBuilder result = new StringBuilder();
                result.append("## Task Plan\n\n");
                
                for (int i = 0; i < plan.size(); i++) {
                    String step = plan.get(i);
                    result.append(String.format("%d. %s\n", i + 1, step));
                    
                    // 创建子任务
                    Map<String, Object> subtask = new HashMap<>();
                    subtask.put("task_id", "subtask_" + (i + 1));
                    subtask.put("instruction", step);
                    subtask.put("status", "pending");
                    subtasks.add(subtask);
                }
                
                result.append("\n## Execution\n\n");
                result.append("Plan generated. Ready to execute subtasks.\n");
                
                return AgentResponse.builder()
                        .answer(result.toString())
                        .success(true)
                        .info(Map.of("subtasks", subtasks, "total_steps", plan.size()))
                        .build();
                
            } catch (Exception e) {
                log.error("Error in PlanMinion execution", e);
                return AgentResponse.builder()
                        .success(false)
                        .error(e.getMessage())
                        .build();
            }
        });
    }
    
    @Override
    public void executeStream(Consumer<StreamChunk> chunkConsumer) {
        String query = getQuery();
        
        chunkConsumer.accept(StreamChunk.builder()
                .content("📋 Creating task plan...")
                .chunkType(StreamChunk.ChunkType.THOUGHT)
                .build());
        
        try {
            // 生成计划
            List<String> plan = generatePlan(query);
            
            // 流式输出每个步骤
            chunkConsumer.accept(StreamChunk.builder()
                    .content("## Task Plan\n")
                    .chunkType(StreamChunk.ChunkType.LLM_OUTPUT)
                    .build());
            
            for (int i = 0; i < plan.size(); i++) {
                String step = plan.get(i);
                chunkConsumer.accept(StreamChunk.builder()
                        .content(String.format("%d. %s\n", i + 1, step))
                        .chunkType(StreamChunk.ChunkType.LLM_OUTPUT)
                        .build());
                
                // 模拟执行延迟
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // 完成
            chunkConsumer.accept(StreamChunk.finalAnswer(
                    String.format("Plan generated with %d steps. Ready for execution.", plan.size())));
            
        } catch (Exception e) {
            chunkConsumer.accept(StreamChunk.error(e.getMessage()));
        }
    }
    
    /**
     * 生成任务计划
     */
    private List<String> generatePlan(String query) {
        // 这里应该调用 LLM 生成计划，暂时使用模拟
        List<String> plan = new ArrayList<>();
        
        // 根据查询类型生成不同的计划
        if (query.toLowerCase().contains("分析") || query.toLowerCase().contains("analyze")) {
            plan.add("Read and understand the input data");
            plan.add("Identify key patterns and features");
            plan.add("Perform statistical analysis");
            plan.add("Generate visualizations if needed");
            plan.add("Summarize findings");
            plan.add("Provide recommendations");
        } else if (query.toLowerCase().contains("文件") || query.toLowerCase().contains("file")) {
            plan.add("Load the file content");
            plan.add("Determine file type and structure");
            plan.add("Extract relevant information");
            plan.add("Process and transform data as needed");
            plan.add("Generate summary or report");
        } else {
            plan.add("Understand the problem statement");
            plan.add("Identify required resources and tools");
            plan.add("Break down into sub-tasks");
            plan.add("Execute each sub-task");
            plan.add("Combine results");
            plan.add("Validate and finalize output");
        }
        
        return plan;
    }
    
    /**
     * 获取子任务列表
     */
    public List<Map<String, Object>> getSubtasks() {
        return subtasks;
    }
}

