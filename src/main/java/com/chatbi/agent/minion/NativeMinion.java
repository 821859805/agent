package com.chatbi.agent.minion;

import com.chatbi.agent.main.Brain;
import com.chatbi.agent.types.AgentResponse;
import com.chatbi.agent.types.Input;
import com.chatbi.agent.types.StreamChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * NativeMinion - 原生 Minion
 * 
 * 直接调用 LLM 获取答案，适用于简单的问答场景。
 * 类似于 Python minion 中的 NativeMinion。
 */
@Slf4j
public class NativeMinion extends WorkerMinion {
    
    public NativeMinion() {
        super();
    }
    
    public NativeMinion(Input input, Brain brain) {
        super(input, brain);
    }
    
    @Override
    public CompletableFuture<AgentResponse> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String query = getQuery();
                
                // 直接调用 LLM
                // 这里简化处理，实际应该构建完整的消息格式
                AgentResponse response = AgentResponse.builder()
                        .answer(query)  // 临时返回查询作为答案
                        .success(true)
                        .build();
                
                return response;
                
            } catch (Exception e) {
                log.error("Error in NativeMinion execution", e);
                return AgentResponse.builder()
                        .success(false)
                        .error(e.getMessage())
                        .build();
            }
        });
    }
    
    @Override
    public void executeStream(Consumer<StreamChunk> chunkConsumer) {
        chunkConsumer.accept(StreamChunk.llmOutput("Processing with NativeMinion..."));
        
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

