package com.chatbi.agent.tools;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 最终答案工具
 * 
 * 用于标记任务完成并返回最终答案。
 * 当Agent调用此工具时，表示任务已完成。
 */
@Slf4j
public class FinalAnswerTool extends BaseTool {
    
    public FinalAnswerTool() {
        this.name = "final_answer";
        this.description = "Provides a final answer to the given problem. " +
                          "Use this tool when you have completed the task and want to provide the final result. " +
                          "The answer should be a complete and accurate response to the original query.";
        this.outputType = "any";
        this.readonly = true;
        
        // 定义输入参数
        Map<String, Object> answerParam = new HashMap<>();
        answerParam.put("type", "any");
        answerParam.put("description", "The final answer to provide. Can be any type (string, number, list, etc.)");
        answerParam.put("required", true);
        
        this.inputs = new HashMap<>();
        this.inputs.put("answer", answerParam);
    }
    
    @Override
    protected Object forward(Object... args) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("FinalAnswerTool requires an answer argument");
        }
        
        Object answer = args[0];
        log.info("Final answer provided: {}", answer);
        
        // 返回答案，Agent框架会处理终止逻辑
        return answer;
    }
    
    @Override
    protected Object forwardWithMap(Map<String, Object> kwargs) {
        Object answer = kwargs.get("answer");
        if (answer == null) {
            throw new IllegalArgumentException("FinalAnswerTool requires an 'answer' argument");
        }
        return forward(answer);
    }
    
    /**
     * 格式化输出
     */
    @Override
    public String formatForObservation(Object output) {
        return String.format("Final Answer: %s", output);
    }
}

