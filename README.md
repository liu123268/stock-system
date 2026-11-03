# 基点

基点是一个面向家庭成员和小型研究组的共享自选股与观点协作平台，也是一个用于学习和展示 Java 后端与 AI Agent 应用开发能力的模块化单体项目。

## 在线体验

**访问地址：[https://134.175.137.81/](https://134.175.137.81/)**

无需下载或本地部署，直接使用浏览器访问。首次使用可[注册账号](https://134.175.137.81/register)，再[登录](https://134.175.137.81/login)，创建自己的研究空间，或通过邀请码加入已有空间，体验共享自选与观点记录。

V1 核心业务已有云端发布；V2 研究助手已完成限定范围的本机验收，尚未发布云端，具体覆盖、未完成事项与验证证据见 [当前状态](docs/project/status.md)。

## 主要功能

- 账号认证与研究空间：JWT 认证、成员邀请、角色权限、空间归档及空间间的数据隔离。
- 共享研究：A／港股目录、共享自选、研究周期和可追溯的成员观点时间线。
- 行情与目录同步：后端接入 Longbridge，提供报价、K 线、分时及每日目录／交易状态核实，Redis 缓存与受控降级降低重复回源。
- 研究助手：在当前账号有权访问的周期内梳理观点、调用受限查询工具、核对引用，分区／分段预览后保存完整结果，支持停止与刷新恢复。
- 限定报告检索：已核实的两份 2025 年报通过本地 Embedding 与 Qdrant 检索，返回可查看原段和页码的依据；另有绑定账号与周期的本机只读 MCP 入口。

本机操作从 [本地启动](docs/engineering/development.md#4-一键启动) 开始，展示时可按 [本机演示顺序](docs/engineering/development.md#本机演示顺序) 操作；项目讲述与能力证据见 [项目亮点](docs/career/highlights.md)。

开发后端启动后，在 `http://localhost:8082/swagger-ui.html` 浏览按当前路由与 DTO 生成的接口文档；JSON／YAML 导出、JWT 调试及 SSE 约定见 [本地接口文档](docs/engineering/api.md)。

## 技术栈

- 后端：Java 17、Spring Boot、Spring Security、MyBatis-Plus、MySQL、Redis/Lettuce、Flyway、Longbridge Java SDK（本地）／OpenAPI CLI（生产）
- Agent：Spring AI、DeepSeek、SSE、本地 Embedding、Qdrant、MCP Java SDK；模型开关与费用预算由后端控制
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Axios、Element Plus、ECharts
- 测试：JUnit Jupiter、MockMvc、Vitest、浏览器冒烟测试

## 仓库结构

```text
stock-system/
├─ AGENTS.md   Codex自动读取的项目协作入口
├─ .run/       IntelliJ共享启动配置
├─ src/        Spring Boot后端源码、配置和测试
├─ frontend/   Vue前端工程
├─ docs/       项目、产品、阶段规格、架构、工程和求职文档
├─ scripts/    本地启动与浏览器测试脚本
└─ pom.xml     Maven依赖和构建配置
```

后端采用经典分层结构，前端采用按文件职责分类的结构。详细说明见[项目结构与文件职责](docs/engineering/project-structure.md)。

Vue 页面经 Spring Security 进入业务 Service，MySQL 保存业务资料与个人问答，Redis 服务于行情缓存。研究助手先取得授权范围，再通过 Spring AI 请求模型；模型提出工具调用，Java 校验并执行既有 Service，报告检索单独使用 Qdrant。模型不直接访问数据库，Qdrant 也不替代业务库。模块关系与数据关系分别见 [架构总览](docs/architecture/overview.md) 和 [数据模型](docs/architecture/data-model.md)。

## 文档入口

- [完整文档导航](docs/index.md)
- [项目协作入口](AGENTS.md)
- [当前开发状态](docs/project/status.md)
- [项目开发规则](docs/project/project-rules.md)
- [实际技术栈](docs/architecture/technology-stack.md)
- [本地开发与启动](docs/engineering/development.md)
- [部署与上线检查](docs/engineering/deployment.md)
- [V1产品需求](docs/product/requirements-v1.md)
- [V2 Agent需求与边界](docs/product/requirements-v2.md)
- [股票与研究需求规格](docs/specs/002-stock-research/requirements.md)
- [研究Agent需求规格](docs/specs/004-research-agent/requirements.md)
- [V1数据模型](docs/architecture/data-model.md)

项目仍处于开发阶段。当前不执行真实交易，也不构成投资建议。

界面设计工具从根目录 [PRODUCT.md](PRODUCT.md) 获取摘要与权威文档链接；产品目的、视觉规范和业务需求在对应 `docs` 文档维护。
