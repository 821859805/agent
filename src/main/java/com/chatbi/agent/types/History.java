package com.chatbi.agent.types;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 历史记录管理类
 * 
 * 管理Agent对话历史，支持：
 * - 消息存储和检索
 * - 历史记录遍历
 * - 消息格式转换
 */
@Data
public class History implements Iterable<Map<String, Object>> {
    /**
     * 消息列表
     */
    private List<Map<String, Object>> messages = new ArrayList<>();
    
    /**
     * 添加消息
     */
    public void append(Map<String, Object> message) {
        messages.add(message);
    }
    
    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", content);
        messages.add(message);
    }
    
    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        messages.add(message);
    }
    
    /**
     * 添加系统消息
     */
    public void addSystemMessage(String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "system");
        message.put("content", content);
        messages.add(message);
    }
    
    /**
     * 添加带元数据的消息
     */
    public void addMessage(String role, String content, Map<String, Object> metadata) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        if (metadata != null) {
            message.put("metadata", metadata);
        }
        messages.add(message);
    }
    
    /**
     * 获取消息数量
     */
    public int size() {
        return messages.size();
    }
    
    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }
    
    /**
     * 清空历史记录
     */
    public void clear() {
        messages.clear();
    }
    
    /**
     * 获取指定索引的消息
     */
    public Map<String, Object> get(int index) {
        return messages.get(index);
    }
    
    /**
     * 获取最后N条消息
     */
    public List<Map<String, Object>> getLastN(int n) {
        if (n >= messages.size()) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - n, messages.size()));
    }
    
    /**
     * 获取最后一条消息
     */
    public Map<String, Object> getLast() {
        if (messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }
    
    /**
     * 转换为OpenAI消息格式
     */
    public List<Map<String, Object>> toOpenAIFormat() {
        List<Map<String, Object>> openAIMessages = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Map<String, Object> formatted = new HashMap<>();
            formatted.put("role", msg.get("role"));
            formatted.put("content", msg.get("content"));
            openAIMessages.add(formatted);
        }
        return openAIMessages;
    }
    
    /**
     * 获取所有系统消息
     */
    public List<Map<String, Object>> getSystemMessages() {
        List<Map<String, Object>> systemMessages = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("system".equals(msg.get("role"))) {
                systemMessages.add(msg);
            }
        }
        return systemMessages;
    }
    
    /**
     * 获取所有用户消息
     */
    public List<Map<String, Object>> getUserMessages() {
        List<Map<String, Object>> userMessages = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("user".equals(msg.get("role"))) {
                userMessages.add(msg);
            }
        }
        return userMessages;
    }
    
    /**
     * 获取所有助手消息
     */
    public List<Map<String, Object>> getAssistantMessages() {
        List<Map<String, Object>> assistantMessages = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("assistant".equals(msg.get("role"))) {
                assistantMessages.add(msg);
            }
        }
        return assistantMessages;
    }
    
    @Override
    public Iterator<Map<String, Object>> iterator() {
        return messages.iterator();
    }
    
    /**
     * 创建副本
     */
    public History copy() {
        History copy = new History();
        for (Map<String, Object> msg : messages) {
            copy.append(new HashMap<>(msg));
        }
        return copy;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("History[").append(messages.size()).append(" messages]:\n");
        for (Map<String, Object> msg : messages) {
            sb.append("  ").append(msg.get("role")).append(": ");
            String content = String.valueOf(msg.get("content"));
            if (content.length() > 100) {
                sb.append(content.substring(0, 100)).append("...");
            } else {
                sb.append(content);
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}

