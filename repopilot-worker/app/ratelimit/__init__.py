"""Redis + Lua 分布式限流（TASK-014）。

模块边界：
* :mod:`app.ratelimit.policy`   —— 策略参数与校验（无 IO）
* :mod:`app.ratelimit.bucket`   —— Redis 连接与 Lua 脚本执行（唯一 IO 点）
* :mod:`app.ratelimit.metrics`  —— 进程内计数（allowed / denied / unavailable）
* :mod:`app.ratelimit.judge_guard` —— 包在 JudgeClient 外层的限流装饰器

设计上不在 ``app/__init__.py`` 里导出任何名字：``bucket`` 会 ``import redis``，
让"只想读指标"的调用方（例如状态接口）被动依赖 Redis 客户端库是不必要的耦合。
"""
