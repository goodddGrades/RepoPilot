"""Agent Runner 目录（V1 不实现真实 Runner）。

后续任务（TASK-004）将在此定义统一执行接口与 MockAgentRunner：

    class AgentRunner(ABC):
        @abstractmethod
        async def run(self, context) -> ...: ...

约束：
* 在线轨迹必须由真实执行产生，不允许随机编造评分；
* Mock 结果必须显式标记 ``execution_provider = MOCK``；
* 不访问公司内网或真实第三方数据源。
"""
