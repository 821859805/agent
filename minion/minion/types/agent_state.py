"""
Agent State Types - Agent 状态类型定义

本模块定义了强类型的 Agent 状态类，用于替代弱类型的 Dict[str, Any] 方式。

主要优势：
- 类型安全：编译时类型检查
- 清晰的字段定义：每个字段都有明确的类型和说明
- IDE 自动补全：提供更好的开发体验
- 更好的文档：自动生成 API 文档
- 内置序列化/反序列化：通过 Pydantic 自动处理
"""

from typing import List, Optional, Any, Dict
from pydantic import BaseModel, Field, validator, ConfigDict
from ..main.input import Input
from .history import History


class AgentState(BaseModel):
    """
    Agent 基础状态类 - 使用强类型字段定义
    
    这是所有 Agent 状态的基础类，用于管理 Agent 的执行状态。
    它替代了弱类型的 Dict[str, Any] 状态，提供以下优势：
    - 类型安全：编译时类型检查，减少运行时错误
    - 清晰的字段定义：每个字段都有明确的类型和说明
    - IDE 自动补全：提供更好的开发体验
    - 更好的文档：自动生成 API 文档
    - 内置序列化/反序列化：通过 Pydantic 自动处理 JSON 转换
    
    使用示例：
        state = AgentState(
            task="完成某个任务",
            input=Input(query="用户查询"),
            agent=my_agent
        )
    """
    
    # ==================== Agent 引用 ====================
    agent: Optional[Any] = Field(
        default=None, 
        description="Agent 实例的引用（使用 Any 类型避免循环导入）"
    )
    """Agent 实例的引用，用于在状态中访问 Agent 的方法和属性"""
    
    # ==================== 核心执行状态 ====================
    history: History = Field(
        default_factory=History, 
        description="对话历史记录，使用 OpenAI 消息格式"
    )
    """对话历史记录，存储用户和助手的交互消息，格式为 OpenAI messages 格式"""
    
    step_count: int = Field(
        default=0, 
        description="已执行的步数"
    )
    """已执行的步数，用于跟踪 Agent 的执行进度"""
    
    error_count: int = Field(
        default=0, 
        description="遇到的错误数量"
    )
    """遇到的错误数量，用于错误统计和反思触发"""
    
    # ==================== 任务和输入 ====================
    task: Optional[str] = Field(
        default=None, 
        description="任务描述"
    )
    """任务描述，存储用户要完成的主要任务"""
    
    input: Optional[Input] = Field(
        default=None, 
        description="当前输入对象"
    )
    """当前输入对象，包含用户查询、配置等信息"""
    
    # ==================== 完成状态 ====================
    is_final_answer: bool = Field(
        default=False, 
        description="是否已达到最终答案"
    )
    """是否已达到最终答案的标志，用于判断任务是否完成"""
    
    final_answer_value: Optional[Any] = Field(
        default=None, 
        description="最终答案的值"
    )
    """最终答案的值，当任务完成时存储最终结果"""
    
    # ==================== 置信度和反思 ====================
    last_confidence: float = Field(
        default=1.0, 
        description="最后一步的置信度分数"
    )
    """最后一步的置信度分数，范围通常为 0.0-1.0，用于评估回答的可靠性"""
    
    # ==================== 附加元数据 ====================
    metadata: Dict[str, Any] = Field(
        default_factory=dict, 
        description="附加的元数据字典"
    )
    """附加的元数据字典，可以存储任意自定义信息"""
    
    # 允许任意类型（如 Input 等自定义类型）
    model_config = ConfigDict(arbitrary_types_allowed=True)
    
    def reset(self) -> None:
        """
        重置状态到初始值
        
        将所有状态字段重置为初始值，但保留 Agent 引用。
        用于开始新的任务或清理状态。
        
        注意：
        - 会保留 agent 引用，避免丢失 Agent 实例
        - 会清空所有历史记录、任务信息、错误计数等
        """
        # 重置时保留 agent 引用
        agent_ref = self.agent
        self.history = History()
        self.step_count = 0
        self.error_count = 0
        self.task = None
        self.input = None
        self.is_final_answer = False
        self.final_answer_value = None
        self.last_confidence = 1.0
        self.metadata = {}
        self.agent = agent_ref


class CodeAgentState(AgentState):
    """
    CodeAgent 扩展状态类 - 包含代码执行相关的字段
    
    这是 CodeAgent 专用的状态类，扩展了 AgentState，添加了代码执行相关的字段。
    用于跟踪代码执行结果、日志和反思状态。
    
    主要扩展功能：
    - 代码执行结果存储：存储每次代码执行的输出
    - 代码执行日志：存储代码执行的日志信息
    - 最终答案标记：标记哪些代码块产生了最终答案
    - 反思状态跟踪：跟踪反思的次数和时机
    
    使用示例：
        state = CodeAgentState(
            task="使用代码解决问题",
            input=Input(query="计算斐波那契数列", route="code"),
            agent=code_agent
        )
        state.add_code_result(0, result_value, "执行日志", False)
    """
    
    # ==================== 代码执行结果 ====================
    code_results: Dict[str, Any] = Field(
        default_factory=dict, 
        description="代码执行结果字典，键为 'code_result_{index}'，值为执行输出"
    )
    """代码执行结果字典，存储每次代码执行的返回值"""
    
    code_logs: Dict[str, str] = Field(
        default_factory=dict, 
        description="代码执行日志字典，键为 'code_logs_{index}'，值为日志内容"
    )
    """代码执行日志字典，存储每次代码执行的输出日志（如 print 语句的输出）"""
    
    code_final_answers: Dict[str, bool] = Field(
        default_factory=dict, 
        description="代码最终答案标记字典，键为 'is_final_answer_{index}'，值为布尔值"
    )
    """代码最终答案标记字典，标记哪些代码块的执行结果被识别为最终答案"""
    
    # ==================== 反思状态 ====================
    reflection_count: int = Field(
        default=0, 
        description="已执行的反思次数"
    )
    """已执行的反思次数，用于跟踪 Agent 的自我反思频率"""
    
    last_reflection_step: int = Field(
        default=0, 
        description="最后一次反思的步数"
    )
    """最后一次反思的步数，用于判断是否需要再次反思"""
    
    def reset(self) -> None:
        """
        重置状态，包括代码相关的字段
        
        重置所有状态字段，包括继承自 AgentState 的字段和 CodeAgentState 特有的字段。
        用于开始新的任务或清理状态。
        """
        super().reset()
        self.code_results = {}
        self.code_logs = {}
        self.code_final_answers = {}
        self.reflection_count = 0
        self.last_reflection_step = 0
    
    def add_code_result(self, index: int, output: Any, logs: str, is_final_answer: bool) -> None:
        """
        添加代码执行结果
        
        将代码执行的结果、日志和最终答案标记存储到状态中。
        用于后续步骤中引用之前的代码执行结果。
        
        Args:
            index: 代码块的索引（从 0 开始）
            output: 代码执行的返回值
            logs: 代码执行的日志输出（如 print 语句的输出）
            is_final_answer: 是否被识别为最终答案
            
        示例：
            state.add_code_result(0, 42, "计算结果: 42", False)
            state.add_code_result(1, "答案", "找到最终答案", True)
        """
        self.code_results[f'code_result_{index}'] = output
        self.code_logs[f'code_logs_{index}'] = logs
        self.code_final_answers[f'is_final_answer_{index}'] = is_final_answer
    
    def get_code_result(self, index: int) -> tuple[Any, str, bool]:
        """
        根据索引获取代码执行结果
        
        从状态中检索指定索引的代码执行结果、日志和最终答案标记。
        
        Args:
            index: 代码块的索引（从 0 开始）
            
        Returns:
            tuple[Any, str, bool]: 包含三个元素的元组：
                - output: 代码执行的返回值，如果不存在则返回 None
                - logs: 代码执行的日志输出，如果不存在则返回空字符串
                - is_final_answer: 是否被识别为最终答案，如果不存在则返回 False
                
        示例：
            output, logs, is_final = state.get_code_result(0)
            if is_final:
                print(f"最终答案: {output}")
        """
        output = self.code_results.get(f'code_result_{index}')
        logs = self.code_logs.get(f'code_logs_{index}', '')
        is_final_answer = self.code_final_answers.get(f'is_final_answer_{index}', False)
        return output, logs, is_final_answer


