"""
Agent Response Types - Agent 响应类型定义

本模块定义了 Agent 系统的响应数据模型，包括：
- Usage: Token 使用统计和成本计算
- StreamChunk: 流式输出块基类
- AgentResponse: Agent 执行步骤的响应结果（替代原有的 5-tuple 格式）
- 各种消息类型：用户消息、助手消息、工具调用消息等
"""

import time
import uuid
from typing import Dict, Any, Optional, List, Union
from dataclasses import dataclass, field


@dataclass
class Usage:
    """
    Token 使用信息类 - 用于单次 API 调用的统计
    
    跟踪 LLM API 调用的 token 使用情况，并计算成本。
    支持 Anthropic 的提示缓存功能（cache creation 和 cache read）。
    
    使用示例：
        usage = Usage(input_tokens=100, output_tokens=50)
        cost = usage.calculate_cost(
            input_cost_per_token=0.000003,
            output_cost_per_token=0.000015
        )
    """
    input_tokens: int = 0
    """输入 token 数量（提示词中的 token 数）"""
    
    output_tokens: int = 0
    """输出 token 数量（模型生成的 token 数）"""
    
    cache_creation_input_tokens: int = 0
    """缓存创建时的输入 token 数（Anthropic 提示缓存功能）"""
    
    cache_read_input_tokens: int = 0
    """缓存读取时的输入 token 数（Anthropic 提示缓存功能）"""
    
    cost_usd: Optional[float] = None
    """成本（美元），根据 token 数和模型定价计算（不是从 API 获取）"""

    @property
    def total_tokens(self) -> int:
        """
        总 token 数（输入 + 输出）
        
        Returns:
            int: 输入 token 和输出 token 的总和
        """
        return self.input_tokens + self.output_tokens

    def calculate_cost(
        self,
        input_cost_per_token: float,
        output_cost_per_token: float,
        cache_read_cost_per_token: Optional[float] = None,
        cache_write_cost_per_token: Optional[float] = None,
    ) -> float:
        """
        根据 token 数量和定价计算成本
        
        计算公式：
        - 输入成本 = input_tokens * input_cost_per_token
        - 输出成本 = output_tokens * output_cost_per_token
        - 缓存读取成本 = cache_read_input_tokens * cache_read_cost_per_token
        - 缓存写入成本 = cache_creation_input_tokens * cache_write_cost_per_token
        - 总成本 = 以上各项之和

        Args:
            input_cost_per_token: 每个输入 token 的成本（例如：0.000003 表示 $3/M）
            output_cost_per_token: 每个输出 token 的成本（例如：0.000015 表示 $15/M）
            cache_read_cost_per_token: 每个缓存读取 token 的成本（默认：输入的 10%）
            cache_write_cost_per_token: 每个缓存写入 token 的成本（默认：输入的 125%）

        Returns:
            float: 总成本（美元）
            
        示例：
            usage = Usage(input_tokens=1000, output_tokens=500)
            cost = usage.calculate_cost(
                input_cost_per_token=0.000003,  # $3 per million
                output_cost_per_token=0.000015  # $15 per million
            )  # 返回: 0.0105 (约 $0.01)
        """
        if cache_read_cost_per_token is None:
            cache_read_cost_per_token = input_cost_per_token * 0.1
        if cache_write_cost_per_token is None:
            cache_write_cost_per_token = input_cost_per_token * 1.25

        cost = (
            self.input_tokens * input_cost_per_token +
            self.output_tokens * output_cost_per_token +
            self.cache_read_input_tokens * cache_read_cost_per_token +
            self.cache_creation_input_tokens * cache_write_cost_per_token
        )
        return cost

    def calculate_cost_from_model(self, model_name: str) -> Optional[float]:
        """
        使用模型定价信息计算成本（从 litellm 价格数据库获取）
        
        自动从模型名称获取定价信息，然后计算成本。
        如果模型未找到或导入失败，返回 None。

        Args:
            model_name: 模型名称（例如：'gpt-4o', 'claude-3-5-sonnet-20241022'）

        Returns:
            Optional[float]: 总成本（美元），如果模型未找到则返回 None
            
        示例：
            usage = Usage(input_tokens=1000, output_tokens=500)
            cost = usage.calculate_cost_from_model('gpt-4o')
        """
        try:
            from minion.utils.model_price import get_model_price
            price_info = get_model_price(model_name)
            if price_info:
                self.cost_usd = self.calculate_cost(
                    input_cost_per_token=price_info['prompt'],
                    output_cost_per_token=price_info['completion'],
                )
                return self.cost_usd
        except ImportError:
            pass
        return None

    @staticmethod
    def get_default_pricing(model_name: str) -> Dict[str, float]:
        """
        获取常见模型的默认定价（备用方案）
        
        当无法从 litellm 价格数据库获取定价时，使用此方法作为备用。
        返回包含 'input' 和 'output' 每个 token 成本的字典。
        
        支持的模型包括：
        - Anthropic: claude-3-5-sonnet, claude-3-5-haiku, claude-3-opus, claude-sonnet-4
        - OpenAI: gpt-4o, gpt-4o-mini, o1, o1-mini
        - 默认: Claude 3.5 Haiku 的定价

        Args:
            model_name: 模型名称

        Returns:
            Dict[str, float]: 包含 'input' 和 'output' 键的字典，值为每个 token 的成本
            
        示例：
            pricing = Usage.get_default_pricing('gpt-4o')
            # 返回: {'input': 0.0000025, 'output': 0.00001}
        """
        PRICING = {
            # Anthropic (per token)
            'claude-3-5-sonnet': {'input': 0.000003, 'output': 0.000015},
            'claude-3-5-haiku': {'input': 0.0000008, 'output': 0.000004},
            'claude-3-opus': {'input': 0.000015, 'output': 0.000075},
            'claude-sonnet-4': {'input': 0.000003, 'output': 0.000015},
            # OpenAI
            'gpt-4o': {'input': 0.0000025, 'output': 0.00001},
            'gpt-4o-mini': {'input': 0.00000015, 'output': 0.0000006},
            'o1': {'input': 0.000015, 'output': 0.00006},
            'o1-mini': {'input': 0.000003, 'output': 0.000012},
            # Default (Claude 3.5 Haiku)
            'default': {'input': 0.0000008, 'output': 0.000004},
        }

        model_lower = model_name.lower()
        for key in PRICING:
            if key in model_lower:
                return PRICING[key]
        return PRICING['default']

    def add(self, other: 'Usage') -> 'Usage':
        """
        累加另一个 Usage 的值到当前对象 (in-place)

        Args:
            other: 要累加的 Usage 对象

        Returns:
            self (支持链式调用)
        """
        self.input_tokens += other.input_tokens
        self.output_tokens += other.output_tokens
        self.cache_creation_input_tokens += other.cache_creation_input_tokens
        self.cache_read_input_tokens += other.cache_read_input_tokens
        # cost_usd 不累加，需要最后重新计算
        return self

    def __add__(self, other: 'Usage') -> 'Usage':
        """
        支持 usage1 + usage2 语法，返回新对象
        """
        return Usage(
            input_tokens=self.input_tokens + other.input_tokens,
            output_tokens=self.output_tokens + other.output_tokens,
            cache_creation_input_tokens=self.cache_creation_input_tokens + other.cache_creation_input_tokens,
            cache_read_input_tokens=self.cache_read_input_tokens + other.cache_read_input_tokens,
        )


@dataclass
class StreamChunk:
    """
    流式输出块基类 - 单个流式输出数据块
    
    用于表示流式输出中的单个数据块，支持两种模式：
    - partial=True: Token 流（增量文本，追加到之前的内容）
    - partial=False: 完整消息（完整消息，替换之前的 partial chunks）
    
    支持的 chunk_type 类型：
    - text: 普通文本
    - thinking: 思考/推理内容
    - tool_call: 工具调用请求
    - tool_result: 工具执行结果
    - observation: 观察结果
    - error: 错误信息
    - agent_response: Agent 响应
    - final_answer: 最终答案
    - completion: 完成标记
    
    使用示例：
        chunk = StreamChunk(
            content="Hello",
            chunk_type="text",
            partial=True
        )
    """
    content: str
    """块的内容（文本或数据）"""
    
    chunk_type: str = "text"
    """
    块类型，可选值：
    - text: 普通文本
    - thinking: 思考/推理内容
    - tool_call: 工具调用请求
    - tool_result: 工具执行结果
    - observation: 观察结果
    - error: 错误信息
    - agent_response: Agent 响应
    - final_answer: 最终答案
    - completion: 完成标记
    """
    
    metadata: Dict[str, Any] = field(default_factory=dict)
    """元数据字典，可以存储任意附加信息"""
    
    timestamp: float = field(default_factory=time.time)
    """时间戳，记录块创建的时间"""
    
    partial: bool = False
    """
    是否为部分内容
    
    - True: Token 流（增量文本，需要追加到之前的内容）
    - False: 完整消息（完整消息，替换之前的 partial chunks）
    """
    
    uuid: str = field(default_factory=lambda: str(uuid.uuid4()))
    """唯一标识符，用于跟踪和关联块"""
    
    usage: Optional[Usage] = None
    """单条消息的 token 使用情况（可选，来自原始 API 响应）"""
    
    model: Optional[str] = None
    """生成此块的模型名称（在多模型场景中很有用）"""


# =============================================================================
# StreamChunk 子类 - 用于不同类型的消息
# =============================================================================

@dataclass
class UserMessage(StreamChunk):
    """
    用户消息类
    
    表示来自用户的消息块。
    """
    chunk_type: str = "user"
    """块类型固定为 'user'"""


@dataclass
class AssistantMessage(StreamChunk):
    """
    助手消息类 - 可包含多个内容块
    
    表示来自助手（Agent）的消息块，可能包含文本、工具调用等。
    """
    chunk_type: str = "assistant"
    """块类型固定为 'assistant'"""
    
    stop_reason: Optional[str] = None
    """
    停止原因，可选值：
    - "end_turn": 正常结束
    - "tool_use": 因为工具调用而停止
    - 其他模型特定的停止原因
    """


@dataclass
class ThinkingMessage(StreamChunk):
    """
    思考/推理消息类 - 扩展思考内容
    
    表示模型的思考过程或推理内容（extended thinking）。
    用于显示模型的内部推理过程。
    """
    chunk_type: str = "thinking"
    """块类型固定为 'thinking'"""
    
    thinking: str = ""
    """思考内容文本"""

    def __post_init__(self):
        """初始化后处理：如果提供了 thinking 但没有 content，则使用 thinking 作为 content"""
        if self.thinking and not self.content:
            self.content = self.thinking


@dataclass
class ToolUseMessage(StreamChunk):
    """
    工具调用消息类
    
    表示工具调用请求，包含工具 ID、名称和输入参数。
    """
    chunk_type: str = "tool_call"
    """块类型固定为 'tool_call'"""
    
    tool_id: str = ""
    """工具调用的唯一标识符"""
    
    tool_name: str = ""
    """工具名称"""
    
    tool_input: Dict[str, Any] = field(default_factory=dict)
    """工具输入参数字典"""

    def __post_init__(self):
        """初始化后处理：将工具信息添加到元数据中"""
        self.metadata.update({
            'tool_id': self.tool_id,
            'tool_name': self.tool_name,
            'tool_input': self.tool_input
        })


@dataclass
class ToolResultMessage(StreamChunk):
    """
    工具执行结果消息类
    
    表示工具执行的结果，可能包含成功的结果或错误信息。
    """
    chunk_type: str = "tool_result"
    """块类型固定为 'tool_result'"""
    
    tool_use_id: str = ""
    """关联的工具调用 ID（对应 ToolUseMessage 的 tool_id）"""
    
    is_error: bool = False
    """是否为错误结果"""

    def __post_init__(self):
        """初始化后处理：将工具结果信息添加到元数据中"""
        self.metadata.update({
            'tool_use_id': self.tool_use_id,
            'is_error': self.is_error
        })


@dataclass
class CodeExecutionMessage(StreamChunk):
    """
    代码执行消息类
    
    表示代码执行块，包含代码、输出和执行状态。
    """
    chunk_type: str = "code"
    """块类型固定为 'code'"""
    
    language: str = "python"
    """编程语言，默认为 'python'"""
    
    code: str = ""
    """要执行的代码"""
    
    output: Optional[str] = None
    """代码执行的输出"""
    
    success: bool = True
    """执行是否成功"""

    def __post_init__(self):
        """初始化后处理：将代码执行信息添加到元数据中"""
        self.metadata.update({
            'language': self.language,
            'code': self.code,
            'output': self.output,
            'success': self.success
        })


@dataclass
class SystemMessage(StreamChunk):
    """
    系统消息类 - 用于初始化、错误等系统级消息
    
    表示系统级别的消息，如初始化信息、错误、警告等。
    """
    chunk_type: str = "system"
    """块类型固定为 'system'"""
    
    subtype: str = ""
    """
    子类型，可选值：
    - "init": 初始化消息
    - "error": 错误消息
    - "warning": 警告消息
    - 其他系统消息类型
    """


@dataclass
class ResultMessage(StreamChunk):
    """
    最终结果消息类 - 标记响应结束
    
    表示整个响应的最终结果，包含执行统计信息。
    
    成本/使用情况信息通过继承的 StreamChunk.usage: Usage 获取：
    - usage.cost_usd: 总成本
    - usage.input_tokens / output_tokens: token 统计
    
    使用示例：
        result = ResultMessage(
            content="任务完成",
            subtype="success",
            duration_ms=1500,
            usage=Usage(input_tokens=100, output_tokens=50)
        )
    """
    chunk_type: str = "completion"
    """块类型固定为 'completion'"""
    
    subtype: str = "success"
    """
    子类型，可选值：
    - "success": 成功完成
    - "error": 错误终止
    - "interrupted": 中断
    """
    
    duration_ms: int = 0
    """执行持续时间（毫秒）"""
    
    is_error: bool = False
    """是否发生错误"""
    
    num_turns: int = 0
    """对话轮数"""
    
    session_id: str = ""
    """会话 ID"""
    
    # usage 继承自 StreamChunk，类型为 Optional[Usage]
    """Token 使用情况（继承自 StreamChunk）"""


# =============================================================================
# 类型别名
# =============================================================================

# 消息类型联合类型别名
# 表示所有可能的消息类型，用于类型提示和类型检查
Message = Union[
    UserMessage,           # 用户消息
    AssistantMessage,      # 助手消息
    ThinkingMessage,       # 思考消息
    ToolUseMessage,        # 工具调用消息
    ToolResultMessage,     # 工具结果消息
    CodeExecutionMessage,  # 代码执行消息
    SystemMessage,         # 系统消息
    ResultMessage,         # 结果消息
]
@dataclass
class AgentResponse(StreamChunk):
    """
    Agent 执行步骤的响应结果类 - 替换原有的 5-tuple 格式
    
    这个类替代了原有的 5-tuple 格式 (response, score, terminated, truncated, info)，
    提供了更结构化和可扩展的方式。
    
    关键字段说明（响应内容）：
        raw_response: LLM 的原始输出，未经处理。
                      例如 CodeMinion: "thought:... ```python code``` <end_code>"
                      保留完整推理过程，便于调试和审计。

        answer: 从原始响应中提取的最终答案。
                例如 CodeMinion 的 final_answer(4) 调用后，answer=4
                这是用户通常需要的干净结果。

        content: UI 显示用（继承自 StreamChunk，自动派生）。
                 - 当 is_final_answer=True 时，content = str(answer)
                 - 否则 content = str(raw_response)
                 用户一般不需要直接访问此字段。

    Usage 跟踪：
        - self.usage (继承自 StreamChunk): 单条消息的 usage
        - self.total_usage: 整个 agent 运行的汇总 usage

    其他字段：
        is_final_answer: 标识 answer 是否为最终答案
        score: 执行质量指标
        confidence: 置信度
        terminated: 是否终止
        truncated: 是否被截断
        info: 扩展信息字典
        error: 错误信息
        execution_time: 执行时间 (ms)
        total_usage: 汇总的 token usage 和 cost
        
    使用示例：
        # 从 5-tuple 创建
        response = AgentResponse.from_tuple(
            ("答案：42", 0.9, True, False, {"answer": 42})
        )
        
        # 直接创建
        response = AgentResponse(
            raw_response="思考过程...",
            answer=42,
            is_final_answer=True,
            score=0.9
        )
    """

    # ==================== 内容字段 ====================
    content: str = ""
    """
    UI 显示内容（继承自 StreamChunk，自动派生）
    
    自动从 answer 或 raw_response 派生：
    - 当 is_final_answer=True 时，content = str(answer)
    - 否则 content = str(raw_response)
    """

    raw_response: Any = None
    """
    LLM 的原始输出（thought + code 等完整内容）
    
    保留完整的推理过程，便于调试和审计。
    例如 CodeMinion 的输出："thought:... ```python code``` <end_code>"
    """

    answer: Optional[Any] = None
    """
    提取后的最终答案（final_answer 的参数）
    
    例如 CodeMinion 的 final_answer(4) 调用后，answer=4
    这是用户通常需要的干净结果。
    """
    
    is_final_answer: bool = False
    """标识 answer 是否为最终答案"""
    
    # ==================== 质量指标字段 ====================
    score: float = 0.0
    """执行质量指标，范围通常为 0.0-1.0"""
    
    confidence: float = 1.0
    """置信度，范围通常为 0.0-1.0，表示答案的可靠性"""
    
    # ==================== 终止状态字段 ====================
    terminated: bool = False
    """是否终止，表示任务是否已完成"""
    
    truncated: bool = False
    """是否被截断，表示响应是否因长度限制而被截断"""
    
    # ==================== 扩展信息字段 ====================
    info: Dict[str, Any] = field(default_factory=dict)
    """扩展信息字典，可以存储任意自定义信息"""
    
    error: Optional[str] = None
    """错误信息，如果执行过程中发生错误"""
    
    # ==================== 执行统计字段 ====================
    execution_time: Optional[float] = None
    """执行时间（毫秒）"""
    
    tokens_used: Optional[int] = None
    """已使用的 token 数（已弃用，请使用 total_usage 代替）"""

    # ==================== Usage 统计字段 ====================
    total_usage: Optional[Usage] = None
    """
    汇总的 usage（所有 API 调用的累计）
    
    注意：
    - 继承的 self.usage 是单条消息的 usage
    - total_usage 是整个 agent 运行期间的汇总
    """
    
    def __post_init__(self):
        """
        初始化 StreamChunk 字段（基于 AgentResponse 内容）
        
        自动设置 content 和 chunk_type：
        - content: 如果是最终答案，显示 answer（干净结果）；否则显示 raw_response（用于调试/中间步骤）
        - chunk_type: 根据状态自动设置（error、final_answer、completion、agent_response）
        """
        # 设置 UI 显示内容：
        # - 如果是最终答案，显示 answer（干净结果）
        # - 否则显示 raw_response（用于调试/中间步骤）
        if not hasattr(self, 'content') or not self.content:
            if self.is_final_answer and self.answer is not None:
                self.content = str(self.answer)
            elif self.raw_response is not None:
                self.content = str(self.raw_response)
            else:
                self.content = ""

        # 设置适当的 chunk_type
        if not hasattr(self, 'chunk_type') or not self.chunk_type:
            if self.error:
                self.chunk_type = "error"
            elif self.is_final_answer:
                self.chunk_type = "final_answer"  
            elif self.terminated:
                self.chunk_type = "completion"
            else:
                self.chunk_type = "agent_response"
        
        # 确保元数据包含 AgentResponse 信息
        if not hasattr(self, 'metadata'):
            self.metadata = {}
        self.metadata.update({
            'score': self.score,
            'confidence': self.confidence,
            'terminated': self.terminated,
            'truncated': self.truncated,
            'is_final_answer': self.is_final_answer
        })
    
    @classmethod
    def from_tuple(cls, tuple_result) -> 'AgentResponse':
        """
        从原有的 5-tuple 格式创建 AgentResponse 实例
        
        用于向后兼容，将旧的 5-tuple 格式转换为新的 AgentResponse 对象。
        
        Args:
            tuple_result: 
                - (response, score, terminated, truncated, info) 格式的结果
                - 或已有的 AgentResponse 实例（直接返回）
            
        Returns:
            AgentResponse: AgentResponse 实例
            
        示例：
            # 从 5-tuple 创建
            tuple_result = ("答案：42", 0.9, True, False, {"answer": 42})
            response = AgentResponse.from_tuple(tuple_result)
        """
        # 如果输入已经是 AgentResponse，直接返回
        if isinstance(tuple_result, cls):
            return tuple_result
        
        if not isinstance(tuple_result, tuple) or len(tuple_result) < 5:
            # 如果不是标准格式，创建一个基本的响应
            return cls(raw_response=tuple_result)
        
        response, score, terminated, truncated, info = tuple_result
        
        # 从 info 中提取特殊字段
        answer = info.get('answer', info.get('final_answer')) if isinstance(info, dict) else None
        is_final_answer = info.get('is_final_answer', False) if isinstance(info, dict) else False
        error = info.get('error') if isinstance(info, dict) else None
        
        return cls(
            raw_response=response,
            score=score,
            terminated=terminated,
            truncated=truncated,
            info=info if isinstance(info, dict) else {},
            answer=answer,
            is_final_answer=is_final_answer,
            error=error
        )
    
    def to_tuple(self) -> tuple:
        """
        转换为原有的 5-tuple 格式以保持向后兼容
        
        将 AgentResponse 对象转换回旧的 5-tuple 格式，用于与旧代码兼容。
        
        Returns:
            tuple: (response, score, terminated, truncated, info) 格式的元组
            
        示例：
            response = AgentResponse(answer=42, is_final_answer=True)
            tuple_result = response.to_tuple()
            # 返回: ("42", 0.0, True, False, {"answer": 42, "is_final_answer": True})
        """
        # 将特殊字段合并到 info 中
        info = self.info.copy()
        if self.answer is not None:
            info['answer'] = self.answer
            info['final_answer'] = self.answer  # 为了向后兼容
        if self.is_final_answer:
            info['is_final_answer'] = self.is_final_answer
        if self.error:
            info['error'] = self.error
        if self.confidence != 1.0:
            info['confidence'] = self.confidence
        if self.execution_time:
            info['execution_time'] = self.execution_time
        if self.tokens_used:
            info['tokens_used'] = self.tokens_used
        
        return (self.raw_response, self.score, self.terminated, self.truncated, info)
    
    def set_answer(self, value: Any, is_final: bool = True) -> 'AgentResponse':
        """
        设置答案
        
        设置答案值，并可选择标记为最终答案。如果标记为最终答案，会自动设置 terminated=True。
        
        Args:
            value: 答案值
            is_final: 是否为最终答案，默认为 True
            
        Returns:
            AgentResponse: self（支持链式调用）
            
        示例：
            response.set_answer(42, is_final=True)
            # 等价于：
            # response.answer = 42
            # response.is_final_answer = True
            # response.terminated = True
        """
        self.answer = value
        self.is_final_answer = is_final
        if is_final:
            self.terminated = True
        return self
    
    def set_error(self, error_msg: str) -> 'AgentResponse':
        """
        设置错误信息
        
        Args:
            error_msg: 错误消息
            
        Returns:
            AgentResponse: self（支持链式调用）
            
        示例：
            response.set_error("执行失败：超时")
        """
        self.error = error_msg
        return self
    
    def is_success(self) -> bool:
        """
        检查执行是否成功（没有错误）
        
        Returns:
            bool: 如果没有错误返回 True，否则返回 False
            
        示例：
            if response.is_success():
                print("执行成功")
        """
        return self.error is None
    
    def is_done(self) -> bool:
        """
        检查任务是否完成
        
        任务完成的判断条件：terminated=True 或 is_final_answer=True
        
        Returns:
            bool: 如果任务完成返回 True，否则返回 False
            
        示例：
            if response.is_done():
                print("任务已完成")
        """
        return self.terminated or self.is_final_answer
    
    def __iter__(self):
        """
        使 AgentResponse 可以像 tuple 一样被解包
        
        这提供了向后兼容性，允许现有代码继续使用 tuple 解包语法。
        
        Returns:
            Iterator: 迭代器，返回 (response, score, terminated, truncated, info)
            
        示例：
            # 向后兼容的用法
            response, score, terminated, truncated, info = agent_response
            
            # 等价于：
            # tuple_result = agent_response.to_tuple()
            # response, score, terminated, truncated, info = tuple_result
        """
        return iter(self.to_tuple()) 