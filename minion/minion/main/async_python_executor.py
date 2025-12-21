#!/usr/bin/env python
# coding=utf-8

# Copyright 2024 The HuggingFace Inc. team. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
异步 Python 代码执行器

本模块提供了异步版本的 Python 代码执行器，支持：
- 异步工具调用（AsyncBaseTool）
- 协程和异步函数执行
- 受限的代码执行环境（沙箱机制）
- 与同步执行器的兼容性

主要组件：
- AsyncPythonExecutor: 异步代码执行器类
- evaluate_async_python_code: 异步代码评估函数
- evaluate_async_ast: AST 节点的异步评估函数
"""

import ast  # Python 抽象语法树模块，用于解析和操作代码结构
import asyncio  # 异步编程支持
import builtins  # Python 内置函数和异常
import difflib  # 用于字符串相似度匹配（错误提示）
import inspect  # 用于检查对象类型（如协程函数）
import logging  # 日志记录
import math  # 数学函数
import re  # 正则表达式
from collections.abc import Callable, Mapping, Awaitable  # 类型提示
from functools import wraps  # 装饰器工具
from importlib import import_module  # 动态导入模块
from types import BuiltinFunctionType, FunctionType, ModuleType  # 类型检查
from typing import Any, Union  # 类型提示

# 从同步执行器导入基础工具和常量
from .local_python_executor import (
    BASE_BUILTIN_MODULES,  # 基础允许导入的模块列表
    MAX_LENGTH_TRUNCATE_CONTENT,  # 内容截断最大长度
    BASE_PYTHON_TOOLS,  # 基础 Python 工具
    DANGEROUS_MODULES,  # 危险模块列表
    DANGEROUS_FUNCTIONS,  # 危险函数列表
    ERRORS,  # 错误类型映射
    DEFAULT_MAX_LEN_OUTPUT,  # 默认最大输出长度
    MAX_OPERATIONS,  # 最大操作次数限制
    MAX_WHILE_ITERATIONS,  # while 循环最大迭代次数
    PrintContainer,  # 打印输出容器
    BreakException,  # break 语句异常
    ContinueException,  # continue 语句异常
    ReturnException,  # return 语句异常
    FinalAnswerException,  # 最终答案异常
    InterpreterError,  # 解释器错误
    truncate_content,  # 截断内容函数
    check_safer_result,  # 检查结果安全性
    safer_func,  # 安全函数包装器
    get_iterable,  # 获取可迭代对象
    fix_final_answer_code,  # 修复最终答案代码
    build_import_tree,  # 构建导入树
    check_import_authorized,  # 检查导入授权
    get_safe_module,  # 获取安全模块
    custom_print,  # 自定义打印函数
    nodunder_getattr,  # 不以下划线开头的属性获取
)
from ..tools.async_base_tool import AsyncBaseTool, SyncToAsyncToolAdapter  # 异步工具基类

logger = logging.getLogger(__name__)  # 日志记录器


def async_safer_eval(func: Callable):
    """
    异步安全评估装饰器
    
    增强评估函数的安全性，通过检查返回值来防止潜在的安全风险。
    Async decorator to enhance the security of an evaluation function by checking its return value.

    Args:
        func (Callable): 需要增强安全性的异步评估函数
                        Async evaluation function to be made safer.

    Returns:
        Callable: 带有返回值检查的安全评估函数
                 Safer evaluation function with return value check.
    """

    @wraps(func)
    async def _check_return(
        expression,
        state,
        static_tools,
        custom_tools,
        authorized_imports=BASE_BUILTIN_MODULES,
    ):
        # 执行原始评估函数
        result = await func(expression, state, static_tools, custom_tools, authorized_imports=authorized_imports)
        # 检查返回值的安全性（防止返回危险对象）
        check_safer_result(result, static_tools, authorized_imports)
        return result

    return _check_return


def create_async_function(
    func_def: Union[ast.FunctionDef, ast.AsyncFunctionDef],
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Callable:
    """
    从 AST 函数定义创建函数（同步或异步）
    
    根据函数定义的类型（同步或异步），创建相应的可调用函数对象。
    Create a function (sync or async) from AST FunctionDef or AsyncFunctionDef
    
    Args:
        func_def: AST 函数定义节点（同步或异步）
        state: 执行状态字典
        static_tools: 静态工具字典
        custom_tools: 自定义工具字典
        authorized_imports: 授权的导入模块列表
        
    Returns:
        创建的函数对象（可能是同步或异步函数）
    """
    source_code = ast.unparse(func_def)  # 将 AST 节点转换回源代码字符串
    is_async = isinstance(func_def, ast.AsyncFunctionDef)  # 判断是否为异步函数

    if is_async:
        # 创建异步函数
        async def new_async_func(*args: Any, **kwargs: Any) -> Any:
            func_state = state.copy()  # 复制状态，避免修改原始状态
            arg_names = [arg.arg for arg in func_def.args.args]  # 获取参数名列表
            default_values = []
            # 评估默认参数值（可能是异步表达式）
            for d in func_def.args.defaults:
                default_values.append(await evaluate_async_ast(d, state, static_tools, custom_tools, authorized_imports))

            # 应用默认值：将默认值与参数名配对
            defaults = dict(zip(arg_names[-len(default_values) :], default_values))

            # 设置位置参数
            for name, value in zip(arg_names, args):
                func_state[name] = value

            # 设置关键字参数
            for name, value in kwargs.items():
                func_state[name] = value

            # 处理可变位置参数（*args）
            if func_def.args.vararg:
                vararg_name = func_def.args.vararg.arg
                func_state[vararg_name] = args

            # 处理可变关键字参数（**kwargs）
            if func_def.args.kwarg:
                kwarg_name = func_def.args.kwarg.arg
                func_state[kwarg_name] = kwargs

            # 为未提供的参数设置默认值
            for name, value in defaults.items():
                if name not in func_state:
                    func_state[name] = value

            # 更新函数状态中的 self 和 __class__（用于类方法）
            if func_def.args.args and func_def.args.args[0].arg == "self":
                if args:
                    func_state["self"] = args[0]
                    func_state["__class__"] = args[0].__class__

            result = None
            try:
                # 执行函数体中的每条语句
                for stmt in func_def.body:
                    result = await evaluate_async_ast(stmt, func_state, static_tools, custom_tools, authorized_imports)
            except ReturnException as e:
                # 捕获 return 语句异常，获取返回值
                result = e.value

            # __init__ 方法不返回任何值
            if func_def.name == "__init__":
                return None

            return result
        
        # 存储原始 AST、源代码和函数名（用于调试和错误报告）
        new_async_func.__ast__ = func_def
        new_async_func.__source__ = source_code
        new_async_func.__name__ = func_def.name
        return new_async_func
    else:
        # 对于同步函数，使用现有的同步创建函数
        from .local_python_executor import create_function
        return create_function(func_def, state, static_tools, custom_tools, authorized_imports)


def evaluate_async_function_def(
    func_def: Union[ast.FunctionDef, ast.AsyncFunctionDef],
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Callable:
    """
    评估函数定义（同步或异步）并将其添加到自定义工具中
    
    当代码中定义函数时，将其创建并注册到 custom_tools 中，以便后续调用。
    Evaluate a function definition (sync or async) and add it to custom tools
    
    Args:
        func_def: AST 函数定义节点
        state: 执行状态字典
        static_tools: 静态工具字典
        custom_tools: 自定义工具字典（函数会被添加到这里）
        authorized_imports: 授权的导入模块列表
        
    Returns:
        创建的函数对象
    """
    # 创建函数并注册到自定义工具字典中
    custom_tools[func_def.name] = create_async_function(func_def, state, static_tools, custom_tools, authorized_imports)
    return custom_tools[func_def.name]


async def evaluate_async_call(
    call: ast.Call,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Any:
    """
    异步版本的函数调用评估器
    
    处理同步和异步工具调用，支持多种函数调用形式（直接调用、属性调用、lambda 等）。
    Async version of evaluate_call that handles both sync and async tool calls.
    
    Args:
        call: AST 调用节点
        state: 执行状态字典
        static_tools: 静态工具字典
        custom_tools: 自定义工具字典
        authorized_imports: 授权的导入模块列表
        
    Returns:
        函数调用的返回值（可能是普通值或协程对象）
    """
    # 检查函数表达式类型是否合法
    if not isinstance(call.func, (ast.Call, ast.Lambda, ast.Attribute, ast.Name, ast.Subscript)):
        raise InterpreterError(f"This is not a correct function: {call.func}).")

    func, func_name = None, None

    # 根据函数表达式的类型，解析出实际的函数对象
    if isinstance(call.func, ast.Call):
        # 函数本身也是调用结果（如 func()()）
        func = await evaluate_async_ast(call.func, state, static_tools, custom_tools, authorized_imports)
    elif isinstance(call.func, ast.Lambda):
        # Lambda 表达式
        func = await evaluate_async_ast(call.func, state, static_tools, custom_tools, authorized_imports)
    elif isinstance(call.func, ast.Attribute):
        # 属性访问调用（如 obj.method()）
        obj = await evaluate_async_ast(call.func.value, state, static_tools, custom_tools, authorized_imports)
        func_name = call.func.attr
        if not hasattr(obj, func_name):
            raise InterpreterError(f"Object {obj} has no attribute {func_name}")
        func = getattr(obj, func_name)
    elif isinstance(call.func, ast.Name):
        # 直接函数名调用（如 func()）
        func_name = call.func.id
        # 按优先级查找函数：状态变量 -> 静态工具 -> 自定义工具 -> 错误类型
        if func_name in state:
            func = state[func_name]
        elif func_name in static_tools:
            func = static_tools[func_name]
        elif func_name in custom_tools:
            func = custom_tools[func_name]
        elif func_name in ERRORS:
            func = ERRORS[func_name]
        else:
            raise InterpreterError(
                f"Forbidden function evaluation: '{call.func.id}' is not among the explicitly allowed tools or defined/imported in the preceding code"
            )
    elif isinstance(call.func, ast.Subscript):
        # 下标调用（如 func[key]()）
        func = await evaluate_async_ast(call.func, state, static_tools, custom_tools, authorized_imports)
        if not callable(func):
            raise InterpreterError(f"This is not a correct function: {call.func}).")
        func_name = None

    # 评估位置参数（支持 *args 展开）
    args = []
    for arg in call.args:
        if isinstance(arg, ast.Starred):
            # 展开 *args
            args.extend(await evaluate_async_ast(arg.value, state, static_tools, custom_tools, authorized_imports))
        else:
            args.append(await evaluate_async_ast(arg, state, static_tools, custom_tools, authorized_imports))

    # 评估关键字参数
    kwargs = {}
    for keyword in call.keywords:
        kwargs[keyword.arg] = await evaluate_async_ast(keyword.value, state, static_tools, custom_tools, authorized_imports)

    # 特殊处理：super() 调用
    if func_name == "super":
        if not args:
            if "__class__" in state and "self" in state:
                return super(state["__class__"], state["self"])
            else:
                raise InterpreterError("super() needs at least one argument")
        cls = args[0]
        if not isinstance(cls, type):
            raise InterpreterError("super() argument 1 must be type")
        if len(args) == 1:
            return super(cls)
        elif len(args) == 2:
            instance = args[1]
            return super(cls, instance)
        else:
            raise InterpreterError("super() takes at most 2 arguments")
    # 特殊处理：print() 调用（捕获输出）
    elif func_name == "print":
        state["_print_outputs"] += " ".join(map(str, args)) + "\n"
        return None
    else:  # 普通函数调用
        # 检查是否为异步工具
        if isinstance(func, AsyncBaseTool):
            # 返回协程对象，由 ast.Await 节点处理 await
            return func(*args, **kwargs) #don't await, let ast.Await handle it
        # 检查是否为协程函数
        elif asyncio.iscoroutinefunction(func):
            # 返回协程对象，由 ast.Await 节点处理 await
            return func(*args, **kwargs) #don't await, let ast.Await handle it
        # 普通同步函数调用
        else:
            if func is None:
                raise InterpreterError(f"Function '{func_name}' is None and cannot be called")
            # 安全检查：禁止调用未明确允许的内置函数
            if (inspect.getmodule(func) == builtins) and inspect.isbuiltin(func) and (func not in static_tools.values()):
                raise InterpreterError(
                    f"Invoking a builtin function that has not been explicitly added as a tool is not allowed ({func_name})."
                )
            return func(*args, **kwargs)


async def evaluate_async_attribute(
    expression: ast.Attribute,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Any:
    """
    评估属性访问表达式（如 obj.attr）
    
    禁止访问双下划线属性（dunder attributes）以增强安全性。
    """
    # 安全检查：禁止访问双下划线属性（如 __class__, __dict__ 等）
    if expression.attr.startswith("__") and expression.attr.endswith("__"):
        raise InterpreterError(f"Forbidden access to dunder attribute: {expression.attr}")
    # 评估对象值
    value = await evaluate_async_ast(expression.value, state, static_tools, custom_tools, authorized_imports)
    # 获取属性值
    return getattr(value, expression.attr)


async def evaluate_async_subscript(
    subscript: ast.Subscript,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Any:
    """
    评估下标访问表达式（如 obj[key] 或 list[index]）
    
    提供友好的错误提示，当键不存在时建议相似的键。
    """
    # 评估索引/键值
    index = await evaluate_async_ast(subscript.slice, state, static_tools, custom_tools, authorized_imports)
    # 评估被索引的对象
    value = await evaluate_async_ast(subscript.value, state, static_tools, custom_tools, authorized_imports)
    try:
        return value[index]
    except (KeyError, IndexError, TypeError) as e:
        # 构建错误消息
        error_message = f"Could not index {value} with '{index}': {type(e).__name__}: {e}"
        # 如果是字典键错误，提供相似键的建议
        if isinstance(index, str) and isinstance(value, Mapping):
            close_matches = difflib.get_close_matches(index, list(value.keys()))
            if len(close_matches) > 0:
                error_message += f". Maybe you meant one of these indexes instead: {str(close_matches)}"
        raise InterpreterError(error_message) from e


async def evaluate_async_name(
    name: ast.Name,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Any:
    """
    评估变量名表达式
    
    按优先级查找变量：状态变量 -> 静态工具 -> 自定义工具 -> 错误类型。
    如果找不到，会尝试查找相似的变量名（用于友好的错误提示）。
    """
    # 按优先级查找变量
    if name.id in state:
        return state[name.id]
    elif name.id in static_tools:
        tool = static_tools[name.id]
        # 如果是异步工具，直接返回
        if isinstance(tool, AsyncBaseTool):
            return tool
        else:
            # 同步工具需要包装为安全函数
            return safer_func(tool, static_tools=static_tools, authorized_imports=authorized_imports)
    elif name.id in custom_tools:
        return custom_tools[name.id]
    elif name.id in ERRORS:
        return ERRORS[name.id]
    # 如果找不到，尝试查找相似的变量名（用于错误提示）
    close_matches = difflib.get_close_matches(name.id, list(state.keys()))
    if len(close_matches) > 0:
        return state[close_matches[0]]
    raise InterpreterError(f"The variable `{name.id}` is not defined.")


async def evaluate_async_condition(
    condition: ast.Compare,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> bool | object:
    """
    评估比较表达式（如 a < b < c）
    
    支持链式比较，如 a < b < c，会按顺序评估每个比较操作。
    """
    result = True
    # 评估左侧操作数
    left = await evaluate_async_ast(condition.left, state, static_tools, custom_tools, authorized_imports)
    # 遍历所有比较操作（支持链式比较）
    for i, (op, comparator) in enumerate(zip(condition.ops, condition.comparators)):
        op = type(op)  # 获取操作符类型
        # 评估右侧操作数
        right = await evaluate_async_ast(comparator, state, static_tools, custom_tools, authorized_imports)
        # 根据操作符类型执行相应的比较
        if op == ast.Eq:
            current_result = left == right
        elif op == ast.NotEq:
            current_result = left != right
        elif op == ast.Lt:
            current_result = left < right
        elif op == ast.LtE:
            current_result = left <= right
        elif op == ast.Gt:
            current_result = left > right
        elif op == ast.GtE:
            current_result = left >= right
        elif op == ast.Is:
            current_result = left is right
        elif op == ast.IsNot:
            current_result = left is not right
        elif op == ast.In:
            current_result = left in right
        elif op == ast.NotIn:
            current_result = left not in right
        else:
            raise InterpreterError(f"Unsupported comparison operator: {op}")

        # 如果任何一个比较为 False，整个表达式为 False
        if current_result is False:
            return False
        # 累积结果（链式比较需要所有比较都为 True）
        result = current_result if i == 0 else (result and current_result)
        # 下一个比较的左侧是当前比较的右侧
        left = right
    return result


async def evaluate_async_for(
    for_loop: ast.For,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Any:
    """
    评估 for 循环语句
    
    支持 break 和 continue 语句，以及 else 子句。
    """
    result = None
    # 评估迭代对象
    iterator = await evaluate_async_ast(for_loop.iter, state, static_tools, custom_tools, authorized_imports)
    # 遍历迭代器
    for counter in iterator:
        # 设置循环变量值
        await set_async_value(
            for_loop.target,
            counter,
            state,
            static_tools,
            custom_tools,
            authorized_imports,
        )
        # 执行循环体
        for node in for_loop.body:
            try:
                line_result = await evaluate_async_ast(node, state, static_tools, custom_tools, authorized_imports)
                if line_result is not None:
                    result = line_result
            except BreakException:
                # 遇到 break，跳出循环
                break
            except ContinueException:
                # 遇到 continue，继续下一次迭代
                continue
        else:
            # for 循环的 else 子句：如果循环正常结束（没有 break），执行这里
            continue
        # 如果执行了 break，跳出外层循环
        break
    return result


async def evaluate_async_if(
    if_statement: ast.If,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> Any:
    """
    评估 if 语句（异步版本）
    
    支持在 if 块中使用 await 语句。
    Async version of evaluate_if that handles await statements in if blocks
    """
    result = None
    
    # 评估条件表达式
    condition_result = await evaluate_async_ast(if_statement.test, state, static_tools, custom_tools, authorized_imports)
    
    if condition_result:
        # 执行 if 分支
        for line in if_statement.body:
            line_result = await evaluate_async_ast(line, state, static_tools, custom_tools, authorized_imports)
            if line_result is not None:
                result = line_result
    else:
        # 执行 else 分支（如果存在）
        for line in if_statement.orelse:
            line_result = await evaluate_async_ast(line, state, static_tools, custom_tools, authorized_imports)
            if line_result is not None:
                result = line_result
    
    return result


async def set_async_value(
    target: ast.AST,
    value: Any,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str],
) -> None:
    """
    异步设置变量值
    
    支持多种赋值目标：变量名、元组解包、下标赋值、属性赋值。
    """
    if isinstance(target, ast.Name):
        # 变量赋值（如 x = value）
        # 安全检查：禁止修改静态工具
        if target.id in static_tools:
            raise InterpreterError(f"Trying to modify a static tool '{target.id}' is not allowed.")
        state[target.id] = value
    elif isinstance(target, ast.Tuple):
        # 元组解包赋值（如 x, y = (1, 2)）
        if not isinstance(value, (list, tuple)):
            raise InterpreterError(f"Cannot unpack non-iterable {type(value).__name__} object")
        for i, element in enumerate(target.elts):
            await set_async_value(element, value[i], state, static_tools, custom_tools, authorized_imports)
    elif isinstance(target, ast.Subscript):
        # 下标赋值（如 list[0] = value 或 dict[key] = value）
        obj = await evaluate_async_ast(target.value, state, static_tools, custom_tools, authorized_imports)
        key = await evaluate_async_ast(target.slice, state, static_tools, custom_tools, authorized_imports)
        obj[key] = value
    elif isinstance(target, ast.Attribute):
        # 属性赋值（如 obj.attr = value）
        obj = await evaluate_async_ast(target.value, state, static_tools, custom_tools, authorized_imports)
        setattr(obj, target.attr, value)
    else:
        raise InterpreterError(f"Unsupported assignment target: {type(target).__name__}")


@async_safer_eval
async def evaluate_async_ast(
    expression: ast.AST,
    state: dict[str, Any],
    static_tools: dict[str, Union[Callable, AsyncBaseTool]],
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]],
    authorized_imports: list[str] = BASE_BUILTIN_MODULES,
):
    """
    异步版本的 AST 评估器
    
    这是核心函数，负责评估各种 AST 节点类型，支持异步工具调用和协程。
    Async version of evaluate_ast that handles asynchronous tool calls and coroutines.
    
    Args:
        expression: 要评估的 AST 节点
        state: 执行状态字典
        static_tools: 静态工具字典
        custom_tools: 自定义工具字典
        authorized_imports: 授权的导入模块列表
        
    Returns:
        评估结果（可能是普通值或协程对象）
    """
    # 操作计数检查：防止无限循环或过多计算
    if state.setdefault("_operations_count", {"counter": 0})["counter"] >= MAX_OPERATIONS:
        raise InterpreterError(
            f"Reached the max number of operations of {MAX_OPERATIONS}. Maybe there is an infinite loop somewhere in the code, or you're just asking too many calculations."
        )
    state["_operations_count"]["counter"] += 1
    
    # Import evaluate functions from sync version for non-call operations
    from .local_python_executor import (
        evaluate_assign,
        evaluate_annassign,
        evaluate_augassign,
        evaluate_unaryop,
        evaluate_lambda,
        evaluate_while,
        evaluate_function_def,
        evaluate_class_def,
        evaluate_boolop,
        evaluate_binop,
        evaluate_if,
        evaluate_listcomp,
        evaluate_setcomp,
        evaluate_try,
        evaluate_raise,
        evaluate_assert,
        evaluate_with,
        evaluate_dictcomp,
        evaluate_delete,
        evaluate_import,
    )
    
    common_params = (state, static_tools, custom_tools, authorized_imports)
    
    if isinstance(expression, ast.Call):
        # Handle async function calls
        return await evaluate_async_call(expression, *common_params)
    elif isinstance(expression, ast.Name):
        return await evaluate_async_name(expression, *common_params)
    elif isinstance(expression, ast.Attribute):
        return await evaluate_async_attribute(expression, *common_params)
    elif isinstance(expression, ast.Subscript):
        return await evaluate_async_subscript(expression, *common_params)
    elif isinstance(expression, ast.Compare):
        return await evaluate_async_condition(expression, *common_params)
    elif isinstance(expression, ast.For):
        return await evaluate_async_for(expression, *common_params)
    elif isinstance(expression, ast.If):
        return await evaluate_async_if(expression, *common_params)
    elif isinstance(expression, ast.FunctionDef):
        return evaluate_async_function_def(expression, *common_params)
    elif isinstance(expression, ast.AsyncFunctionDef):
        return evaluate_async_function_def(expression, *common_params)
    elif isinstance(expression, ast.Assign):
        # For assignments, we need async version due to set_async_value
        targets = []
        for target in expression.targets:
            targets.append(target)
        value = await evaluate_async_ast(expression.value, *common_params)
        for target in targets:
            await set_async_value(target, value, *common_params)
        return value
    elif isinstance(expression, ast.Constant):
        return expression.value
    elif isinstance(expression, ast.Tuple):
        elements = []
        for elt in expression.elts:
            elements.append(await evaluate_async_ast(elt, *common_params))
        return tuple(elements)
    elif isinstance(expression, ast.List):
        elements = []
        for elt in expression.elts:
            elements.append(await evaluate_async_ast(elt, *common_params))
        return elements
    elif isinstance(expression, ast.Dict):
        keys = []
        values = []
        for k in expression.keys:
            keys.append(await evaluate_async_ast(k, *common_params))
        for v in expression.values:
            values.append(await evaluate_async_ast(v, *common_params))
        return dict(zip(keys, values))
    elif isinstance(expression, ast.Expr):
        return await evaluate_async_ast(expression.value, *common_params)
    elif isinstance(expression, ast.BinOp):
        left = await evaluate_async_ast(expression.left, *common_params)
        right = await evaluate_async_ast(expression.right, *common_params)
        # Use sync evaluate_binop by recreating the expression with evaluated operands
        # We need to call the sync version directly for binary operations
        from .local_python_executor import evaluate_binop
        return evaluate_binop(expression, state, static_tools, custom_tools, authorized_imports)
    elif isinstance(expression, ast.Return):
        value = None
        if expression.value:
            value = await evaluate_async_ast(expression.value, *common_params)
        raise ReturnException(value)
    elif isinstance(expression, ast.Await):
        # await 表达式处理
        # 先评估值，不立即 await（因为值本身可能是协程函数调用）
        value = await evaluate_async_ast(expression.value, *common_params)
        # 只有当它是协程时才 await
        if inspect.isawaitable(value):
            return await value
        # 如果不是协程，直接返回值（可能是普通值或已完成的协程）
        return value
    elif isinstance(expression, ast.Pass):
        return None
    elif isinstance(expression, ast.Import) or isinstance(expression, ast.ImportFrom):
        # 自定义导入处理：特殊处理 asyncio 模块
        # Custom import handling for asyncio
        if isinstance(expression, ast.Import):
            # import asyncio 或 import asyncio as aio
            for alias in expression.names:
                if alias.name == "asyncio" and "asyncio" in static_tools:
                    # 使用自定义的 asyncio 模块（适配现有事件循环）
                    state[alias.asname or alias.name] = static_tools["asyncio"]
                    return None
        elif isinstance(expression, ast.ImportFrom) and expression.module == "asyncio" and "asyncio" in static_tools:
            # from asyncio import ... 处理
            custom_asyncio = static_tools["asyncio"]
            for alias in expression.names:
                if hasattr(custom_asyncio, alias.name):
                    state[alias.asname or alias.name] = getattr(custom_asyncio, alias.name)
                else:
                    raise Exception(f"Module asyncio has no attribute {alias.name}")
            return None
        
        # 对于其他导入，使用原始的导入处理器
        from .local_python_executor import evaluate_ast
        return evaluate_ast(expression, state, static_tools, custom_tools, authorized_imports)
    else:
        # For other operations, fall back to sync evaluation
        # Most operations don't need async handling
        from .local_python_executor import evaluate_ast
        return evaluate_ast(expression, state, static_tools, custom_tools, authorized_imports)


async def evaluate_async_python_code(
    code: str,
    static_tools: dict[str, Union[Callable, AsyncBaseTool]] | None = None,
    custom_tools: dict[str, Union[Callable, AsyncBaseTool]] | None = None,
    state: dict[str, Any] | None = None,
    authorized_imports: list[str] = BASE_BUILTIN_MODULES,
    max_print_outputs_length: int = DEFAULT_MAX_LEN_OUTPUT,
):
    """
    异步版本的 Python 代码评估函数
    
    这是主要的入口函数，负责解析代码、设置执行环境并执行代码。
    支持异步工具执行和协程处理。
    Async version of evaluate_python_code that handles asynchronous tool execution.
    
    Args:
        code: 要执行的 Python 代码字符串
        static_tools: 静态工具字典（None 时使用空字典）
        custom_tools: 自定义工具字典（None 时使用空字典）
        state: 执行状态字典（None 时创建新字典）
        authorized_imports: 授权的导入模块列表
        max_print_outputs_length: 最大打印输出长度
        
    Returns:
        tuple: (result, is_final_answer)
            - result: 代码执行结果
            - is_final_answer: 是否为最终答案（通过 final_answer 函数调用）
    """
    # 解析代码为 AST
    try:
        expression = ast.parse(code)
    except SyntaxError as e:
        # 语法错误处理：提供友好的错误信息
        raise InterpreterError(
            f"Code parsing failed on line {e.lineno} due to: {type(e).__name__}\n"
            f"{e.text}"
            f"{' ' * (e.offset or 0)}^\n"
            f"Error: {str(e)}"
        )

    # 初始化执行环境
    if state is None:
        state = {}
    static_tools = static_tools.copy() if static_tools is not None else {}  # 复制以避免修改原始字典
    custom_tools = custom_tools if custom_tools is not None else {}
    result = None
    state["_print_outputs"] = PrintContainer()  # 打印输出容器
    state["_operations_count"] = {"counter": 0}  # 操作计数器
    
    # 创建自定义 asyncio 模块以处理现有事件循环中的 asyncio.run()
    # Create a custom asyncio module to handle asyncio.run() in existing event loop
    import types
    import sys
    import asyncio as real_asyncio
    
    # Only create custom module if asyncio is in authorized imports
    if "asyncio" in authorized_imports:
        custom_asyncio = types.ModuleType("asyncio")
        
        # Copy all attributes from real asyncio module
        for attr_name in dir(real_asyncio):
            if not attr_name.startswith("_") or attr_name in ("__name__", "__doc__"):
                try:
                    setattr(custom_asyncio, attr_name, getattr(real_asyncio, attr_name))
                except (AttributeError, TypeError):
                    pass
        
        # 定义在现有事件循环中工作的自定义函数
        # Define custom functions that work within existing event loop
        def custom_run(coro):
            """
            自定义 asyncio.run()，在现有事件循环中工作
            
            由于我们已经在异步上下文中，不能创建新的事件循环。
            相反，我们返回协程对象，由调用者 await。
            Custom asyncio.run() that works within an existing event loop.
            Since we're already in an async context, we can't create a new event loop.
            Instead, we return the coroutine to be awaited by the caller.
            """
            if real_asyncio.iscoroutine(coro):
                # 返回协程本身，将由 AST 评估器 await
                return coro
            elif callable(coro):
                # 如果是可调用对象，先调用它
                result = coro()
                if real_asyncio.iscoroutine(result):
                    return result
                return result
            else:
                # 如果不是协程，直接返回
                return coro
            
        async def custom_gather(*args, **kwargs):
            """Custom asyncio.gather() that properly handles coroutines"""
            # Use the real asyncio.gather but ensure we're handling coroutines properly
            return await real_asyncio.gather(*args, **kwargs)
            
        def custom_create_task(coro, **kwargs):
            """Custom create_task that works in existing event loop"""
            try:
                loop = real_asyncio.get_running_loop()
                return loop.create_task(coro, **kwargs)
            except RuntimeError:
                # If no running loop, just return the coroutine
                return coro
        
        # Override the problematic functions
        custom_asyncio.run = custom_run
        custom_asyncio.gather = custom_gather  
        custom_asyncio.create_task = custom_create_task
        
        # Add to static tools so it's available in the execution environment
        static_tools["asyncio"] = custom_asyncio
        
        # Store the original asyncio module for restoration later
        static_tools["_original_asyncio"] = real_asyncio

    # 特殊处理 final_answer 工具：将其包装为抛出异常的形式
    if "final_answer" in static_tools:
        previous_final_answer = static_tools["final_answer"]

        def final_answer(*args, **kwargs):  # 允许传递任意参数
            #logger.debug(f"final_answer called with args={args}, kwargs={kwargs}")

            # 调用原始 final_answer 函数
            result = previous_final_answer(*args, **kwargs)
            #logger.debug(f"final_answer about to raise FinalAnswerException with result={result}")
            # 抛出异常以中断执行并返回最终答案
            raise FinalAnswerException(result)

        static_tools["final_answer"] = final_answer
        
        # 同时更新 functions 命名空间（如果存在）
        if "functions" in static_tools and hasattr(static_tools["functions"], "final_answer"):
            setattr(static_tools["functions"], "final_answer", final_answer)

    original_asyncio = None
    if "asyncio" in authorized_imports:
        # Save reference to the original module
        original_asyncio = sys.modules.get("asyncio")
    
    try:
        # 执行 AST 中的每个节点
        for node in expression.body:
            result = await evaluate_async_ast(node, state, static_tools, custom_tools, authorized_imports)
        # 截断打印输出（防止过长）
        state["_print_outputs"].value = truncate_content(
            str(state["_print_outputs"]), max_length=max_print_outputs_length
        )
        is_final_answer = False
        return result, is_final_answer
    except FinalAnswerException as e:
        # 捕获最终答案异常（通过 final_answer 函数调用）
        logger.debug(f"Caught FinalAnswerException with value={e.value}")
        state["_print_outputs"].value = truncate_content(
            str(state["_print_outputs"]), max_length=max_print_outputs_length
        )
        is_final_answer = True
        return e.value, is_final_answer
    except Exception as e:
        # 其他异常：提供详细的错误信息
        state["_print_outputs"].value = truncate_content(
            str(state["_print_outputs"]), max_length=max_print_outputs_length
        )
        raise InterpreterError(
            f"Code execution failed at line '{ast.get_source_segment(code, node)}' due to: {type(e).__name__}: {e}"
        )
    finally:
        # 恢复原始的 asyncio 模块（如果被替换了）
        # Restore the original asyncio module if it was replaced
        if "asyncio" in authorized_imports and original_asyncio is not None:
            sys.modules["asyncio"] = original_asyncio


class AsyncPythonExecutor:
    """
    异步 Python 代码执行器
    
    在本地环境中执行 Python 代码，支持异步工具。
    该执行器通过限制导入和内置函数的访问来评估 Python 代码，
    使其适合运行不可信代码。它在执行之间维护状态，
    允许异步和同步工具在代码中使用，并分别捕获打印输出和返回值。
    
    Async executor of Python code in a local environment with support for asynchronous tools.

    This executor evaluates Python code with restricted access to imports and built-in functions,
    making it suitable for running untrusted code. It maintains state between executions,
    allows for async and sync tools to be made available to the code, and captures
    print outputs separately from return values.
    """

    def __init__(
        self,
        additional_authorized_imports: list[str],
        max_print_outputs_length: int | None = None,
        additional_functions: dict[str, Callable] | None = None,
    ):
        """
        初始化异步 Python 执行器
        
        Args:
            additional_authorized_imports: 额外授权的导入模块列表
            max_print_outputs_length: 最大打印输出长度（None 时使用默认值）
            additional_functions: 额外的自定义函数字典
        """
        self.custom_tools = {}  # 自定义工具字典（代码中定义的函数）
        self.state = {"__name__": "__main__"}  # 执行状态字典
        self.max_print_outputs_length = max_print_outputs_length
        if max_print_outputs_length is None:
            self.max_print_outputs_length = DEFAULT_MAX_LEN_OUTPUT
        self.additional_authorized_imports = additional_authorized_imports
        # 添加 multi_tool_use, functions, inspect 和 asyncio 到授权导入列表
        # 用于 GPT 并行工具调用和异步支持
        authorized_imports_with_multi_tool = list(set(BASE_BUILTIN_MODULES) | set(self.additional_authorized_imports) | {"multi_tool_use", "inspect", "asyncio", "functions"})
        self.authorized_imports = authorized_imports_with_multi_tool
        self.static_tools = None  # 静态工具字典（通过 send_tools 设置）
        self.additional_functions = additional_functions or {}  # 额外的自定义函数

    async def __call__(self, code_action: str) -> tuple[Any, str, bool]:
        """
        执行 Python 代码
        
        这是执行器的主要调用接口，执行代码并返回结果。
        
        Args:
            code_action: 要执行的 Python 代码字符串
            
        Returns:
            tuple: (output, logs, is_final_answer)
                - output: 代码执行结果
                - logs: 打印输出日志
                - is_final_answer: 是否为最终答案
        """
        max_length = self.max_print_outputs_length if self.max_print_outputs_length is not None else DEFAULT_MAX_LEN_OUTPUT
        # 执行代码
        output, is_final_answer = await evaluate_async_python_code(
            code_action,
            static_tools=self.static_tools,
            custom_tools=self.custom_tools,
            state=self.state,
            authorized_imports=self.authorized_imports,
            max_print_outputs_length=max_length,
        )
        # 获取打印输出
        logs = str(self.state["_print_outputs"])
        return output, logs, is_final_answer

    def send_variables(self, variables: dict):
        """
        发送变量到执行环境
        
        将变量添加到执行状态中，代码可以访问这些变量。
        
        Args:
            variables: 变量字典
        """
        self.state.update(variables)

    def send_tools(self, tools: dict[str, Any]):
        """
        发送工具到异步执行器
        
        自动将同步工具包装在异步适配器中。
        特殊处理 meta 工具（AgentStateAwareTool）- 它们在代码中可用但不暴露给 LLM。
        
        Send tools to the async executor. Automatically wraps sync tools in async adapters.
        Handles meta tools (AgentStateAwareTool) specially - they are available in code but not exposed to LLM.
        
        Args:
            tools: 工具字典，键为工具名，值为工具对象或函数
        """
        from ..tools.base_tool import BaseTool
        import sys
        import types
        
        converted_tools = {}  # 转换后的工具字典（暴露给 LLM）
        meta_tools = {}  # meta 工具字典（代码中可用但不暴露给 LLM）
        
        # 处理传入的工具：分类和转换
        for name, tool in tools.items():
            if isinstance(tool, AsyncBaseTool):
                # 已经是异步工具，直接使用
                converted_tools[name] = tool
            elif hasattr(tool, 'forward') and isinstance(tool, BaseTool):  # BaseTool 实例
                # BaseTool 实例，现在不需要转换（可以直接使用）
                converted_tools[name] = tool
            else:
                # 普通函数，保持原样
                converted_tools[name] = tool
        
        # 注册内置 meta 工具（如 think, plan, reflect）
        meta_tools.update(self._get_builtin_meta_tools())
        
        # 添加 multi_tool_use 模块用于 GPT 的并行工具调用
        from ..tools.multi_tool_use import parallel, smart_parallel
        
        # 创建真实的模块对象用于 multi_tool_use
        multi_tool_use_module = types.ModuleType("multi_tool_use")
        multi_tool_use_module.parallel = smart_parallel  # 使用智能版本以获得更好的兼容性
        
        # 在 sys.modules 中注册模块，以便可以导入
        sys.modules["multi_tool_use"] = multi_tool_use_module
        
        # 创建 meta 工具调用函数
        def meta_call(tool_name: str, *args, **kwargs):
            """
            调用 meta 工具的函数 - 对 LLM 透明
            
            meta 工具是代码中可用但不暴露给 LLM 的工具，
            用于内部状态管理和高级功能。
            """
            if tool_name in meta_tools:
                import asyncio
                import concurrent.futures
                import threading
                
                tool = meta_tools[tool_name]
                
                # 简化的异步处理：使用线程池运行异步任务
                def run_async_tool():
                    try:
                        # 在新的事件循环中运行（避免与现有事件循环冲突）
                        loop = asyncio.new_event_loop()
                        asyncio.set_event_loop(loop)
                        try:
                            return loop.run_until_complete(tool(*args, **kwargs))
                        finally:
                            loop.close()
                    except Exception as e:
                        return {"error": f"Meta tool execution failed: {e}"}
                
                # 使用线程池执行，避免事件循环冲突
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                    future = executor.submit(run_async_tool)
                    try:
                        result = future.result(timeout=30)  # 30秒超时
                        return result
                    except concurrent.futures.TimeoutError:
                        return {"error": "Meta tool execution timeout"}
                    except Exception as e:
                        return {"error": f"Meta tool execution error: {e}"}
            else:
                raise ValueError(f"Meta tool '{tool_name}' not found")
        
        # 合并转换后的工具、基础 Python 工具和额外的 Python 函数
        # Combine converted tools, base Python tools, and additional Python functions first
        self.static_tools = {
            **converted_tools,  # 转换后的工具（暴露给 LLM）
            **meta_tools,  # 添加 meta 工具到 static_tools（代码中可用，但不到 functions 命名空间）
            **BASE_PYTHON_TOOLS.copy(),  # 基础 Python 工具
            **self.additional_functions,  # 额外的自定义函数
            "multi_tool_use": multi_tool_use_module,  # 添加真实的模块对象
            "_meta_call": meta_call,  # 添加 meta 工具调用函数
        }
        
        # 创建 functions 命名空间对象来保存工具（仅包含 LLM 可见的工具）
        # Create a functions namespace object to hold tools (ONLY for LLM-visible tools)
        functions_namespace = types.SimpleNamespace()
        for name, tool in converted_tools.items():  # 注意：只包含 converted_tools，不包含 meta_tools
            # 将工具添加到 functions 命名空间，使用原始名称和函数名
            setattr(functions_namespace, name, tool)
            if hasattr(tool, '__name__'):
                setattr(functions_namespace, tool.__name__, tool)
        
        # 如果 BASE_PYTHON_TOOLS 中存在 final_answer，也添加到 functions 命名空间
        if "final_answer" in BASE_PYTHON_TOOLS:
            setattr(functions_namespace, "final_answer", BASE_PYTHON_TOOLS["final_answer"])
        
        # 将 functions 命名空间添加到 static_tools
        self.static_tools["functions"] = functions_namespace
        
        # 创建自定义 asyncio 模块，修改 run 函数以适配现有事件循环
        # Create custom asyncio module with modified run function
        import types
        import asyncio as real_asyncio
        
        # 创建自定义的 asyncio 模块
        asyncio_module = types.ModuleType("asyncio")
        
        # 从真实的 asyncio 模块复制所有属性
        for attr_name in dir(real_asyncio):
            if not attr_name.startswith("_") or attr_name in ("__name__", "__doc__"):
                try:
                    setattr(asyncio_module, attr_name, getattr(real_asyncio, attr_name))
                except (AttributeError, TypeError):
                    pass
        
        # 重写 run 函数以在我们的环境中工作
        def custom_asyncio_run(coro):
            """
            自定义 asyncio.run() 实现，用于 AsyncPythonExecutor
            
            由于我们已经在事件循环中，不能创建新的事件循环。
            相反，我们返回协程对象，由调用者 await。
            Custom implementation of asyncio.run() for use in AsyncPythonExecutor.
            Since we're already in an event loop, we can't create a new one.
            Instead, we return the coroutine to be awaited by the caller.
            """
            if real_asyncio.iscoroutine(coro):
                # 返回协程本身，将由 AST 评估器 await
                return coro
            elif callable(coro):
                # 如果是可调用对象，先调用它
                result = coro()
                if real_asyncio.iscoroutine(result):
                    return result
                return result
            else:
                # 如果不是协程，直接返回
                return coro
        
        # 定义自定义 gather 函数来处理协程
        async def custom_asyncio_gather(*args, **kwargs):
            """
            自定义 asyncio.gather() 实现，用于 AsyncPythonExecutor
            Custom implementation of asyncio.gather() for use in AsyncPythonExecutor
            """
            return await real_asyncio.gather(*args, **kwargs)
        
        def custom_create_task(coro, **kwargs):
            """
            自定义 create_task，在现有事件循环中工作
            Custom create_task that works in existing event loop
            """
            try:
                loop = real_asyncio.get_running_loop()
                return loop.create_task(coro, **kwargs)
            except RuntimeError:
                # 如果没有运行中的循环，直接返回协程
                return coro
        
        # 设置我们的自定义函数
        asyncio_module.run = custom_asyncio_run
        asyncio_module.gather = custom_asyncio_gather
        asyncio_module.create_task = custom_create_task
        
        # 注册自定义 asyncio 模块
        self.static_tools["asyncio"] = asyncio_module
        
        # 存储原始 asyncio 以便需要时恢复
        self.static_tools["_original_asyncio"] = real_asyncio
        
        # 注意：我们不再全局替换 sys.modules["asyncio"] 以避免冲突
        # 导入处理在 AST 评估级别的 evaluate_async_ast 中完成
        # NOTE: We no longer globally replace sys.modules["asyncio"] to avoid conflicts
        # Import handling is done at AST evaluation level in evaluate_async_ast
        
        # 同时将 multi_tool_use 和 functions 添加到 state 作为全局对象，以便直接访问
        self.state["multi_tool_use"] = multi_tool_use_module
        self.state["functions"] = functions_namespace
        
        # 创建 functions 模块用于直接导入
        functions_module = types.ModuleType("functions")
        for name, tool in converted_tools.items():
            setattr(functions_module, name, tool)
        
        # 在 sys.modules 中注册 functions 模块，以便可以导入
        sys.modules["functions"] = functions_module
        
        self.state["_meta_call"] = meta_call  # 添加到 state 以便代码调用
        self.state["asyncio"] = asyncio_module  # 添加自定义 asyncio 到 state
        
        # 记录 meta 工具信息（用于调试）
        if meta_tools:
            self.state["_meta_tools_available"] = list(meta_tools.keys())
    
    def _get_builtin_meta_tools(self) -> dict[str, Any]:
        """
        获取内置的 meta 工具
        
        meta 工具是代码中可用但不暴露给 LLM 的工具，
        用于内部状态管理和高级功能（如思考、规划、反思）。
        
        Returns:
            meta 工具字典
        """
        from ..tools.think_tool import ThinkTool
        from ..tools.meta_tools import PlanTool, ReflectionTool
        
        return {
            "think": ThinkTool(),      # 思考工具
            "plan": PlanTool(),        # 规划工具
            "reflect": ReflectionTool(),  # 反思工具
        }


__all__ = ["evaluate_async_python_code", "AsyncPythonExecutor"]