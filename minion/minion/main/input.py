#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Input 模块 - 输入数据模型定义

本模块定义了 Agent 系统的输入数据模型和相关类型。
包含输入处理、任务管理、执行状态跟踪等功能。

主要类：
- Input: 核心输入数据模型，包含查询、配置、状态等信息
- Task: 任务模型，用于任务路由和集成策略
- ExecutionState: 执行状态跟踪模型
- PostProcessingType: 后处理类型枚举
- EnsembleStrategyType: 集成策略类型枚举
- QuestionType: 问题类型枚举
"""

import uuid
from enum import Enum
from typing import Any, Dict, Optional, Union, Callable, List
from datetime import datetime

from pydantic import BaseModel, Field, ConfigDict

from minion.utils.utils import extract_number_from_string
from minion.utils.answer_extraction import extract_math_answer, extract_python


class PostProcessingType(str, Enum):
    """
    后处理类型枚举
    
    定义了对答案进行后处理的不同方式，用于从原始答案中提取结构化信息。
    """
    NONE = "none"
    """不进行后处理，直接返回原始答案"""
    
    EXTRACT_NUMBER = "extract_number_from_string"
    """从字符串中提取数字"""
    
    EXTRACT_MATH_ANSWER = "extract_math_answer"
    """从文本中提取数学答案"""
    
    EXTRACT_PYTHON = "extract_python"
    """从文本中提取 Python 代码"""


class EnsembleStrategyType(Enum):
    """
    集成策略类型枚举
    
    定义了多个执行结果如何集成的策略，用于提高答案的可靠性。
    """
    EARLY_STOP = "early_stop"
    """早停策略：一旦得到满意的结果就停止"""
    
    ESTIMATE = "estimate"
    """估计策略：评估哪个结果更好"""
    
    VOTE = "vote"
    """投票策略：通过投票决定最佳结果"""


class QuestionType(Enum):
    """
    问题类型枚举
    
    定义了不同类型的问题，用于针对性地处理不同类型的查询。
    """
    BLANK_FILLING_QUESTION = "blank filling question"
    """填空题"""
    
    TRUE_FALSE_QUESTION = "true-false question"
    """判断题"""
    
    MULTIPLE_CHOICE_QUESTION = "multiple-choice question"
    """选择题"""


class Task(BaseModel):
    """
    任务模型
    
    用于管理任务的执行配置和状态，包括路由、重试次数、集成策略等。
    """
    route: Optional[str] = ""
    """路由信息，用于指定使用哪个 minion 处理任务（临时解决方案）"""
    
    num_trials: int = 1
    """下游节点运行的次数，用于多次尝试提高成功率"""
    
    ensemble_strategy: str = EnsembleStrategyType.EARLY_STOP
    """集成策略，定义多个执行结果如何合并"""
    
    output: Any = None
    """任务输出结果"""
    
    parent: Any = None
    """父任务引用，用于任务树结构"""
    # input : 'Input' = None  # 已注释：输入对象引用


class ExecutionState(BaseModel):
    """
    执行状态模型
    
    用于跟踪任务执行过程中的状态信息，包括当前执行的 minion、迭代次数等。
    """
    current_minion: Optional[str] = None
    """当前正在执行的 minion 名称"""
    
    chosen_minion: Optional[str] = None
    """已选择的 minion 名称"""
    
    current_iteration: int = 0
    """当前迭代次数"""
    
    current_task_index: int = 0
    """当前任务索引"""
    
    last_completed_task: Optional[str] = None
    """最后完成的任务 ID"""
    
    check_result: Optional[Dict[str, Any]] = None
    """检查结果，包含验证操作的输出"""


class Input(BaseModel):
    """
    输入数据模型 - Agent 系统的核心输入类
    
    这是 Agent 系统的主要输入数据模型，包含了执行任务所需的所有信息：
    - 用户查询和上下文
    - 系统配置和提示
    - 工具和资源
    - 答案处理和验证
    - 执行状态跟踪
    
    使用示例：
        input_obj = Input(
            query="计算 234 * 568",
            route="code",
            tools=[my_tool],
            stream=True
        )
    """
    
    # ==================== 核心查询字段 ====================
    query: Union[str, List[Any]] = ""
    """
    用户查询内容
    
    可以是：
    - 字符串：简单的文本查询
    - 列表：多模态内容块，如 [{"type": "text", "content": "what's the solution 234*568"}]
    """
    
    query_type: str = ""
    """查询类型，用于分类和路由不同类型的查询"""
    
    query_id: Optional[Any] = None
    """查询唯一标识符，用于跟踪和关联查询"""
    
    query_time: datetime = Field(default_factory=datetime.utcnow)
    """查询时间戳，记录查询创建的时间"""
    
    # ==================== 系统配置字段 ====================
    system_prompt: Optional[str] = None
    """系统提示词，用于指导 Agent 的行为和响应风格"""
    
    mind_id: Optional[str] = None
    """心智 ID，用于选择不同的推理策略（如 left_mind、right_mind）"""
    
    images: Optional[Any] = None
    """图像输入，支持多模态查询（可以是字符串路径或 base64 编码）"""
    
    tools: List[Any] = Field(default_factory=list)
    """工具列表，Agent 可以使用的工具集合"""
    
    user_id: Optional[str] = None
    """用户 ID，用于多用户场景和权限管理"""
    
    stream: bool = False
    """是否启用流式输出，True 时逐步返回结果而不是一次性返回"""
    
    model_config = ConfigDict(arbitrary_types_allowed=True)
    """Pydantic 配置，允许任意类型（如工具对象）"""

    # ==================== 上下文字段 ====================
    long_context: str = Field(default="")
    """完整上下文，包含所有相关信息"""
    
    short_context: str = ""
    """摘要上下文，经过总结或抽象后的简化版本"""

    query_sub_type: str = ""
    """查询子类型，更具体的查询分类"""
    
    guidance: str = ""
    """处理指导，额外的处理建议或说明"""
    
    constraint: str = ""
    """约束条件，处理查询时必须遵守的限制或要求"""
    
    instruction: str = ""
    """分步指令，详细的步骤说明"""

    # ==================== 任务相关字段 ====================
    cache_plan: str = None
    """缓存的计划，用于存储之前生成的执行计划"""
    
    task: Any = None
    """任务对象引用"""
    
    symbols: Dict[str, Any] = Field(default_factory=dict)
    """符号表，存储变量和符号定义"""
    
    task_check: bool = False
    """是否进行任务检查"""

    # ==================== 答案相关字段 ====================
    answer: str = ""
    """最终提取/处理后的答案"""
    
    answer_raw: str = ""
    """原始答案，包含完整的思维链和推理过程"""
    
    answer_code: str = ""
    """代码格式的答案（如果适用）"""
    
    answer_full: str = ""
    """完整输出，包含所有细节，也可以称为推理内容"""
    
    feedback: str = ""
    """改进反馈，用于后续优化"""
    
    error: str = ""
    """错误信息，用于改进和调试"""
    
    entry_point: str = ""
    """代码生成的入口点函数名称"""

    # ==================== 标准答案字段（用于评估）====================
    ground_truth_raw: Optional[str] = None
    """原始标准答案文本"""
    
    ground_truth: Any = None
    """处理后的标准答案"""
    
    extract_ground_truth: Optional[Callable[[Any], Any]] = None
    """提取/处理标准答案的函数"""
    
    compare_ground_truth: Optional[Callable[[Any, Any], bool]] = None
    """比较答案与标准答案的函数，返回是否匹配"""

    # ==================== 识别字段 ====================
    complexity: str = None
    """复杂度级别：low/medium/high"""
    
    query_range: str = None
    """查询范围：short/long range"""
    
    difficulty: str = None
    """难度级别"""
    
    field: str = None
    """主要领域/域"""
    
    subfield: str = None
    """特定子领域"""

    # ==================== 配置和状态字段 ====================
    question_type: str = ""
    """具体的问题类型"""
    
    answer_protocol: str = ""
    """答案格式化协议，也可以称为 answer_format"""
    
    execution_config: dict = {}
    """执行配置，如集成策略等"""
    
    check: Union[bool, int] = False
    """是否执行验证，可以是布尔值或整数（表示验证次数）"""
    
    check_route: str = ""
    """验证路由，指定使用哪个验证策略"""
    
    improve_route: str = "feedback"
    """改进路由，默认根据反馈进行改进的策略"""

    # ==================== 元数据字段 ====================
    dataset: str = ""
    """源数据集标识符"""
    
    dataset_description: str = ""
    """数据集描述"""
    
    run_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    """唯一执行标识符，用于跟踪单次执行"""

    # ==================== 处理状态字段 ====================
    processed_minions: int = 0
    """已处理此输入的 minion 数量"""
    
    metadata: Dict[str, Any] = Field(
        default_factory=dict,
        description="附加元数据，包括测试用例等"
    )
    """附加元数据字典，可以存储任意自定义信息"""
    
    info: dict = {}
    """附加信息字典"""
    
    route: Optional[str] = ""
    """路由信息，指定使用哪个 minion 或处理策略"""
    
    num_trials: int = 1
    """执行尝试次数，用于多次尝试提高成功率"""
    
    ensemble_strategy: str = EnsembleStrategyType.EARLY_STOP
    """集成处理策略，定义多个执行结果如何合并"""

    # ==================== 执行状态跟踪字段 ====================
    execution_state: ExecutionState = Field(default_factory=ExecutionState)
    """当前执行状态，跟踪任务执行过程中的状态信息"""
    
    pre_processing: str = ""
    """预处理配置"""
    
    post_processing: PostProcessingType = Field(
        default=PostProcessingType.NONE,
        description="要应用的后处理类型"
    )
    """后处理类型，定义如何从原始答案中提取结构化信息"""
    
    save_state: bool = Field(
        default=False,
        description="是否在执行过程中保存状态"
    )
    """是否保存状态，用于持久化和恢复执行状态"""

    def update_execution_state(self, **kwargs):
        """
        更新执行状态
        
        使用提供的键值对更新执行状态对象中的字段。
        
        Args:
            **kwargs: 要更新的键值对，支持的字段包括：
                - current_minion (Optional[str]): 当前正在执行的 minion 名称
                - current_iteration (int): 当前迭代次数
                - current_task_index (int): 当前任务索引
                - last_completed_task (Optional[str]): 最后完成的任务 ID
                - chosen_minion (Optional[str]): 已选择的 minion 名称
                - check_result (Optional[Dict[str, Any]]): 检查操作的结果
                
        Raises:
            ValueError: 如果提供的字段名不是有效的执行状态字段
            
        示例：
            input_obj.update_execution_state(
                current_minion="code_minion",
                current_iteration=1
            )
        """
        for key, value in kwargs.items():
            if hasattr(self.execution_state, key):
                setattr(self.execution_state, key, value)
            else:
                raise ValueError(f"Invalid execution state field: {key}")

    def apply_post_processing(self, answer_raw: str, post_processing=None) -> Any:
        """
        对原始答案应用后处理
        
        根据后处理类型从原始答案中提取结构化信息。
        支持的后处理类型包括：提取数字、提取数学答案、提取 Python 代码等。
        
        Args:
            answer_raw (str): 要处理的原始答案
            post_processing (Optional[PostProcessingType]): 覆盖默认的后处理类型
                如果提供，则使用此值；否则使用实例的 post_processing 字段
            
        Returns:
            Any: 处理后的答案，类型取决于后处理类型：
                - EXTRACT_NUMBER: 数字
                - EXTRACT_MATH_ANSWER: 数学答案
                - EXTRACT_PYTHON: Python 代码字符串
                - NONE: 原始字符串
                
        示例：
            # 从文本中提取数字
            processed = input_obj.apply_post_processing(
                "答案是 42",
                PostProcessingType.EXTRACT_NUMBER
            )  # 返回: 42
            
            # 从文本中提取 Python 代码
            processed = input_obj.apply_post_processing(
                "代码：def func(): return 1",
                PostProcessingType.EXTRACT_PYTHON
            )  # 返回: "def func(): return 1"
        """
        if not answer_raw:
            return answer_raw
            
        # 如果指定了 post_processing，使用它；否则使用实例值
        processing_type = post_processing if post_processing else self.post_processing
            
        if processing_type == PostProcessingType.EXTRACT_NUMBER:
            return extract_number_from_string(answer_raw)
        elif processing_type == PostProcessingType.EXTRACT_MATH_ANSWER:
            return extract_math_answer(answer_raw)
        elif processing_type == PostProcessingType.EXTRACT_PYTHON:
            return extract_python(answer_raw, self.entry_point)
        else:  # PostProcessingType.NONE
            return answer_raw

Task.model_rebuild()
