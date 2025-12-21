package com.chatbi.agent.minion;

import com.chatbi.agent.main.Brain;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.types.StreamChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * CotMinion - Chain-of-Thought Minion
 * 
 * 使用思维链进行推理，适用于需要复杂推理的任务。
 * 类似于 Python minion 中的 CotMinion。
 */
@Slf4j
public class CotMinion extends WorkerMinion {
    
    private static final String COT_PROMPT = """
        Please solve this problem step by step.
        
        Think through each step carefully:
        1. Understand the problem
        2. Break it down into smaller parts
        3. Solve each part
        4. Combine the results
        
        Show your reasoning at each step.
        
        Problem: %s
        
        Let's think step by step:
        """;
    
    public CotMinion() {
        super();
    }
    
    public CotMinion(Input input, Brain brain) {
        super(input, brain);
    }
    
    @Override
    public CompletableFuture<AgentResponse> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String query = getQuery();
                String prompt = String.format(COT_PROMPT, query);
                
                // 调用 LLM 进行推理
                // 这里简化处理
                AgentResponse response = AgentResponse.builder()
                        .answer("Chain-of-Thought reasoning completed")
                        .thinkContent("Step 1: Understanding the problem...\nStep 2: Breaking it down...")
                        .success(true)
                        .build();
                
                return response;
                
            } catch (Exception e) {
                log.error("Error in CotMinion execution", e);
                return AgentResponse.builder()
                        .success(false)
                        .error(e.getMessage())
                        .build();
            }
        });
    }
    
    @Override
    public void executeStream(Consumer<StreamChunk> chunkConsumer) {
        chunkConsumer.accept(StreamChunk.thought("Using Chain-of-Thought reasoning..."));
        
        // 模拟步骤输出
        chunkConsumer.accept(StreamChunk.builder()
                .content("Step 1: Understanding the problem...")
                .chunkType(StreamChunk.ChunkType.THOUGHT)
                .build());
        
        chunkConsumer.accept(StreamChunk.builder()
                .content("Step 2: Breaking it down into parts...")
                .chunkType(StreamChunk.ChunkType.THOUGHT)
                .build());
        
        try {
            AgentResponse response = execute().join();
            if (response.isSuccess()) {
                chunkConsumer.accept(StreamChunk.finalAnswer(response.getAnswer()));
            } else {
                chunkConsumer.accept(StreamChunk.error(response.getError()));
            }
        } catch (Exception e) {
            chunkConsumer.accept(StreamChunk.error(e.getMessage()));
        }
    }
}

