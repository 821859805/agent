package com.chatbi.agent.types;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Input数据模型 - Agent系统的核心输入类
 * 
 * 包含执行任务所需的所有信息：
 * - 用户查询和上下文
 * - 系统配置和提示
 * - 工具和资源
 * - 答案处理和验证
 * - 执行状态跟踪
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Input {
    // ==================== 核心查询字段 ====================
    /**
     * 用户查询内容
     * 可以是字符串或多模态内容块
     */
    private Object query = "";
    
    /**
     * 查询类型，用于分类和路由不同类型的查询
     */
    private String queryType = "";
    
    /**
     * 查询唯一标识符
     */
    private String queryId;
    
    /**
     * 查询时间戳
     */
    private LocalDateTime queryTime = LocalDateTime.now();
    
    // ==================== 系统配置字段 ====================
    /**
     * 系统提示词，用于指导Agent的行为和响应风格
     */
    private String systemPrompt;
    
    /**
     * 心智ID，用于选择不同的推理策略
     */
    private String mindId;
    
    /**
     * 图像输入，支持多模态查询
     */
    private Object images;
    
    /**
     * 工具列表，Agent可以使用的工具集合
     */
    private List<Object> tools = new ArrayList<>();
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 是否启用流式输出
     */
    private Boolean stream = false;
    
    // ==================== 上下文字段 ====================
    /**
     * 完整上下文
     */
    private String longContext = "";
    
    /**
     * 摘要上下文
     */
    private String shortContext = "";
    
    /**
     * 查询子类型
     */
    private String querySubType = "";
    
    /**
     * 处理指导
     */
    private String guidance = "";
    
    /**
     * 约束条件
     */
    private String constraint = "";
    
    /**
     * 分步指令
     */
    private String instruction = "";
    
    // ==================== 任务相关字段 ====================
    /**
     * 缓存的计划
     */
    private String cachePlan;
    
    /**
     * 任务对象引用
     */
    private Object task;
    
    /**
     * 符号表
     */
    private Map<String, Object> symbols = new HashMap<>();
    
    /**
     * 是否进行任务检查
     */
    private Boolean taskCheck = false;
    
    // ==================== 答案相关字段 ====================
    /**
     * 最终提取/处理后的答案
     */
    private String answer = "";
    
    /**
     * 原始答案
     */
    private String answerRaw = "";
    
    /**
     * 代码格式的答案
     */
    private String answerCode = "";
    
    /**
     * 完整输出
     */
    private String answerFull = "";
    
    /**
     * 改进反馈
     */
    private String feedback = "";
    
    /**
     * 错误信息
     */
    private String error = "";
    
    /**
     * 代码入口点
     */
    private String entryPoint = "";
    
    // ==================== 标准答案字段（用于评估）====================
    /**
     * 原始标准答案文本
     */
    private String groundTruthRaw;
    
    /**
     * 处理后的标准答案
     */
    private Object groundTruth;
    
    // ==================== 识别字段 ====================
    /**
     * 复杂度级别：low/medium/high
     */
    private String complexity;
    
    /**
     * 查询范围：short/long range
     */
    private String queryRange;
    
    /**
     * 难度级别
     */
    private String difficulty;
    
    /**
     * 主要领域
     */
    private String field;
    
    /**
     * 特定子领域
     */
    private String subfield;
    
    // ==================== 配置和状态字段 ====================
    /**
     * 具体的问题类型
     */
    private String questionType = "";
    
    /**
     * 答案格式化协议
     */
    private String answerProtocol = "";
    
    /**
     * 执行配置
     */
    private Map<String, Object> executionConfig = new HashMap<>();
    
    /**
     * 是否执行验证
     */
    private Object check = false;
    
    /**
     * 验证路由
     */
    private String checkRoute = "";
    
    /**
     * 改进路由
     */
    private String improveRoute = "feedback";
    
    // ==================== 元数据字段 ====================
    /**
     * 源数据集标识符
     */
    private String dataset = "";
    
    /**
     * 数据集描述
     */
    private String datasetDescription = "";
    
    /**
     * 唯一执行标识符
     */
    private String runId = UUID.randomUUID().toString();
    
    // ==================== 处理状态字段 ====================
    /**
     * 已处理此输入的minion数量
     */
    private Integer processedMinions = 0;
    
    /**
     * 附加元数据字典
     */
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 附加信息字典
     */
    private Map<String, Object> info = new HashMap<>();
    
    /**
     * 路由信息，指定使用哪个minion
     */
    private String route = "";
    
    /**
     * 执行尝试次数
     */
    private Integer numTrials = 1;
    
    /**
     * 集成处理策略
     */
    private String ensembleStrategy = "early_stop";
    
    /**
     * 预处理配置
     */
    private String preProcessing = "";
    
    /**
     * 后处理类型
     */
    private String postProcessing = "none";
    
    /**
     * 是否保存状态
     */
    private Boolean saveState = false;
    
    // ==================== 构造函数 ====================
    
    /**
     * 从查询字符串创建Input
     */
    public Input(String query) {
        this.query = query;
        this.queryId = UUID.randomUUID().toString();
    }
    
    /**
     * 从查询和路由创建Input
     */
    public Input(String query, String route) {
        this.query = query;
        this.route = route;
        this.queryId = UUID.randomUUID().toString();
    }
    
    /**
     * 设置查询（支持字符串类型）
     */
    public void setQuery(String query) {
        this.query = query;
    }
    
    /**
     * 获取查询字符串
     */
    public String getQueryString() {
        if (query instanceof String) {
            return (String) query;
        }
        return query != null ? query.toString() : "";
    }
}

