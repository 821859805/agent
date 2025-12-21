# copy from https://github.com/princeton-nlp/intercode which this file doesn't contains in package,weird.
# but we'll need to modify this anyway.

import ast
import re
from typing import Dict, Tuple

import rpyc

from .ic_env import ACTION_EXEC, AGENT_OBS, EVAL_OBS, REWARD, IntercodeEnv

HOST_PORT = 3006
RESET_KEYWORD = "RESET_CONTAINER_SPECIAL_KEYWORD"


class PythonEnv(IntercodeEnv):
    """
    Python 代码执行环境（基于 Docker 容器）
    Gym environment for python shell
    
    这个类提供了一个基于 Docker 容器的 Python 代码执行环境，通过 rpyc 远程过程调用
    与容器内的 Python 服务器通信，实现代码的隔离执行。
    """

    name = "ic_python"  # 环境名称标识

    def __init__(self, image_name: str, **kwargs):
        """
        初始化 Python 执行环境
        
        Args:
            image_name: Docker 镜像名称，用于创建执行代码的容器
            **kwargs: 其他关键字参数，可能包含 is_agent 等配置
        """
        # 配置端口映射，将容器内的 HOST_PORT 映射到主机的相同端口
        kwargs["ports"] = {f"{HOST_PORT}/tcp": HOST_PORT}
        # 调用父类初始化方法，创建 Docker 容器
        super(PythonEnv, self).__init__(image_name, **kwargs)
        # 通过 rpyc 连接到容器内的 Python 服务器（运行在 HOST_PORT 端口）
        self.conn = rpyc.connect("localhost", HOST_PORT)
        # 标识是否为智能体模式（影响函数定义的交互式输入行为）
        self.is_agent = kwargs.get("is_agent", False)
        # 存储执行信息的字典
        self.info = {}
        # 存储执行轨迹（动作和观察的序列）
        self.trajectory = []

    def reset_container(self) -> None:
        """
        重置容器到初始状态
        
        通过执行特殊的重置关键字，清空容器内的执行环境，恢复到初始状态
        """
        self.conn.root.execute(RESET_KEYWORD)

    def exec_action(self, action: str) -> None:
        """
        执行一个动作（Python 代码）
        
        Args:
            action: 要执行的 Python 代码字符串
            
        执行代码后，结果会存储在 self.observation 中，执行状态存储在 self.info 中
        """
        try:
            # 如果动作是函数定义（以 "def " 开头）
            if action.strip().startswith("def "):
                # 如果不是智能体模式，需要交互式输入多行函数定义
                if not self.is_agent:
                    function_definition = self.input_multiline_function()
                    action = action + "\n" + function_definition
            else:
                pass
            # 记录执行的命令
            self.logger.info(f"Command run: {action}")
            # 通过 rpyc 连接在容器内执行代码，返回包含 output 和 error 的字典
            self.observation = self.conn.root.execute(action)
            # 检查执行结果中是否有错误：如果 observation 包含 "error" 键且错误信息不为空，则标记为执行失败
            self.info[ACTION_EXEC] = "error" in self.observation and len(self.observation["error"]) > 0
        except Exception as err:
            # 如果执行过程中发生异常，将错误信息存储在 observation 中
            self.observation = {"error": f"Error executing action: {err}"}
            # 标记执行失败
            self.info[ACTION_EXEC] = False

    def get_reward(self) -> Tuple[float, Dict]:
        """
        获取当前步骤的奖励值
        
        Returns:
            Tuple[float, Dict]: 奖励值（0.0-1.0）和包含额外信息的字典
            
        根据数据集类型（从 data_path 中提取）调用相应的奖励计算函数
        """
        # 数据集名称到奖励计算函数的映射
        MAP_DATASET_TO_REWARD = {
            "ic_apps": self.get_reward_apps,  # Apps 数据集的奖励计算
            "ic_mbpp": self.get_reward_mbpp,   # MBPP 数据集的奖励计算
        }
        # 从数据路径中提取数据集名称（例如："/path/to/ic_apps.json" -> "ic_apps"）
        dataset = self.data_path.split("/")[-1].split(".")[0]
        # 调用对应的奖励计算函数
        return MAP_DATASET_TO_REWARD[dataset]()

    def close(self):
        """
        关闭环境并清理资源
        
        停止 Docker 容器，释放相关资源
        """
        self.logger.info("Beginning environment shutdown...")
        # 停止 Docker 容器
        self.container.stop()
        self.logger.info("Agent container stopped")

    ############################
    ### MARK: Helper methods ###
    ############################
    def input_multiline_function(self):
        lines = []
        while True:
            line = input(". ")
            if len(line) == 0:
                break
            lines.append(line)
        return "\n".join(lines)

    def wrap_with_print(self, command):
        # Parse the command as an AST (Abstract Syntax Tree)
        parsed_command = ast.parse(command.strip())

        # Check if the command contains an assignment node, print node, or import
        has_assignment = any(isinstance(node, ast.Assign) for node in ast.walk(parsed_command))
        has_print = any(
            isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "print"
            for node in ast.walk(parsed_command)
        )
        has_import = any(isinstance(node, ast.Import) for node in ast.walk(parsed_command))
        is_assert = command.strip().startswith("assert")

        # Wrap the command with "print" if it's not an assignment and does not have a "print" statement
        if not any([has_assignment, has_print, has_import, is_assert]):
            return f"print({command})"
        else:
            return command

    ##############################
    ### MARK: Reward functions ###
    ##############################
    def get_reward_apps(self):
        self.info = {}
        return 0.0, self.info

    def get_reward_mbpp(self):
        self.info = {}

        # Get function from `submit` action
        # TODO: Assert that function name is given upon `submit` action
        last_action = self.trajectory[-1][0]
        func_name = last_action.split(" ")[1]

        # Get gold function name, assign to submitted function
        func_name_ref = re.search(r"def (\w+)\(", self.gold).group(1)
        self.conn.root.execute(f"{func_name_ref} = {func_name}")

        # Run tests against submitted function
        results_pred = {}
        self.conn.root.execute(self.record["test_setup_code"])
        for test in self.record["tests"]:
            results_pred[test] = self.conn.root.execute(test)

        # Load gold + run tests
        results_gold = {}
        self.conn.root.execute(RESET_KEYWORD)
        self.conn.root.execute(self.record["test_setup_code"])
        self.conn.root.execute(self.gold)
        for test in self.record["tests"]:
            results_gold[test] = self.conn.root.execute(test)

        self.info["submitted_function"] = func_name
        self.info[AGENT_OBS] = results_pred
        self.info[EVAL_OBS] = results_gold

        # Compute reward
        correct = 0
        for test, output in results_pred.items():
            output_gold = results_gold[test]
            if output == output_gold:
                correct += 1
        self.info[REWARD] = float(correct) / len(results_pred)
        self.reward = self.info[REWARD]

        self.logger.info(f"Info: {self.info}")
        self.logger.info(f"Reward: {self.reward}")
        return self.reward, self.info
