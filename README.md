# 基点

基点是一个面向家庭成员和小型研究组的共享自选股与观点协作平台，也是一个用于学习和展示 Java 全栈开发能力的模块化单体项目。

已实现认证、共享研究、真实行情和每日目录同步，已发布云端；V1 仍待本地 SDK 真实 Token 自动刷新验收，具体证据与后续任务见 [当前状态](docs/project/status.md)。

## 技术栈

- 后端：Java 17、Spring Boot、Spring Security、MyBatis-Plus、MySQL、Redis/Lettuce、Flyway、Longbridge Java SDK（本地）／OpenAPI CLI（生产）
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Axios、Element Plus、ECharts
- 测试：JUnit 5、MockMvc、Vitest、浏览器冒烟测试

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

## 文档入口

- [完整文档导航](docs/index.md)
- [项目协作入口](AGENTS.md)
- [当前开发状态](docs/project/status.md)
- [项目开发规则](docs/project/project-rules.md)
- [实际技术栈](docs/architecture/technology-stack.md)
- [本地开发与启动](docs/engineering/development.md)
- [部署与上线检查](docs/engineering/deployment.md)
- [V1产品需求](docs/product/requirements-v1.md)
- [V2 Agent方向与待确认规格](docs/product/requirements-v2.md)
- [当前阶段需求规格](docs/specs/002-stock-research/requirements.md)
- [V1数据模型](docs/architecture/data-model.md)

项目仍处于开发阶段。当前不执行真实交易，也不构成投资建议。

界面设计工具从根目录 [PRODUCT.md](PRODUCT.md) 获取摘要与权威文档链接；产品目的、视觉规范和业务需求在对应 `docs` 文档维护。
