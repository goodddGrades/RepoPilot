# 设计文档检查报告

日期：2026-10-17。本报告只检验本包文档和数据结构，不检验尚未获得的本地项目代码。

> 后续修订：2026-10-18 引入 `evaluation_batches`，表数由 26 变为 27，并同步 API 信封与错误码；
> 同日再引入 `evaluation_batch_runs` 与跨实验复合外键，表数为 **28**，见 [task-002-freeze.md](./task-002-freeze.md)。
> 本报告的检查范围为设计包 V1.0 原始状态。

## 已完成

- 26张表：字段名、主键、唯一键、索引字段、外键类型、外键被引用的唯一键和RESTRICT声明一致。
- 数据库CHECK表达式中的字段引用均存在；字段字典与Markdown视图对应。
- 4份JSON Schema通过Draft 2020-12元Schema检查。
- 4份规范示例通过各自Schema；18份反例被拒绝，包含未知版本/字段、错误Trace ID、负顺序号、无时区时间、错误重复编号、缺来源与错误消息载荷。
- 公共OpenAPI YAML可解析，明确只含公共组件；未宣称覆盖全部业务路由。
- TASK-001～012均存在；文档本地链接和代码围栏完整。
- 原TASK-002的差异已单独列出，没有假定本地代码已经存在这些缺陷。

## 重复检查

在本包根目录使用独立Python环境安装`tools/requirements-design.txt`，运行：

```bash
python3 tools/verify_design.py
```

检查输出含PASS/FAIL及具体检查数量。它是设计一致性检查工具，不会启动数据库，也不会执行业务迁移。所需依赖仅用于文档检查，不要求业务Worker引入这些库。

## 未完成及限制

- 未读取Mac上的RepoPilot工程、AGENTS或Flyway历史，不能给出真实项目Commit ID。
- 未执行Flyway、MySQL约束、MyBatis映射、Spring启动、Worker执行或Vue构建。
- 未验证真实Codex入口或LiteLLM特定部署日志，规范JSONL是当前明确支持的输入设计。
- 未实施公网鉴权、多租户或生产沙箱；V1为本地/受控环境设计。
- 静态关系检查不等于MySQL接受最终DDL；本地TASK-002仍需真实数据库验收。

## 审核建议

先对齐本地TASK-001，再审核`task-002-delta.md`，随后按唯一字典实现TASK-002。已执行迁移保留原样，必要修正采用新的前向迁移。
