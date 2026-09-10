# 系统架构总览

更新时间：2026-09-10

## 1. 架构目标

项目采用前后端分离的模块化单体：先用清晰模块边界、数据库约束、事务和测试完成真实业务闭环，再根据可验证场景加入缓存、异步和可观测性。

## 2. 当前运行结构

```text
Browser
  │
  ▼
Vue 3 + TypeScript + Vite
  │  /api development proxy
  ▼
Spring Boot REST API
  │
  ├─ Spring Security + JWT
  ├─ Controller / Service / Mapper
  └─ 行情适配（目录、Mock、Longbridge 报价、K 线与分时）
       └─ Spring Boot 管理的 `longbridge serve` 子进程
  │
  ▼
MySQL 8
```

生产交付阶段计划使用 Nginx 统一托管前端并反向代理 `/api`，使用 Docker Compose 组织必要服务；这些内容尚未实现，不能写成当前事实。

开发／测试端口只在 [开发环境](../engineering/development.md) 维护。

## 3. 核心业务关系

```text
用户
  └─ 通过 workspace_member 加入研究空间
       └─ 空间共享 workspace_stock
            └─ 每次关注建立 research_cycle
                 └─ 每个成员发布自己的 stock_opinion 时间线
```

- 用户身份与空间角色分离；公开注册不能选择空间角色。
- 股票基础信息属于全局标准数据；自选股和观点属于空间业务数据。
- 所有空间业务请求都必须根据当前认证用户验证有效成员关系。
- 同一空间同一股票只有一份共享关注记录，由数据库唯一约束兜底。

## 4. 后端边界

一个 Spring Boot/Maven 部署单元，业务包中组织分层代码。当前目录、命名与依赖方向见 [项目结构](../engineering/project-structure.md)，架构取舍见 [ADR-001](decisions/ADR-001-modular-monolith.md)。认证代码已按业务模块整理，不能再描述为待迁移的全局横向目录。

## 5. 前端边界

一个 Vue 工程；目录职责见 [项目结构](../engineering/project-structure.md)，请求和状态流见 [前端教学](../engineering/frontend.md)。

## 6. 外部行情边界

业务 Service 分别依赖项目自己的 `MarketDataProvider`、`MarketCandlestickProvider` 和 `MarketIntradayProvider`，供应商 DTO 不进入研究事务或 Controller。开发默认由 MySQL 搜索标准股票身份，再通过 Spring Boot 管理的官方 `longbridge serve` 子进程批量补充真实价格，并为已加入空间的单只股票按需读取 K 线或分时；测试继续使用明确的 Mock。当前接口和调用位置见 [SPEC-002 设计](../specs/002-stock-research/design.md)。

当前低频基础报价使用批量请求、并发/速率上限和 5 秒进程内缓存保护外部供应商；当天分时也按股票缓存 5 秒，K 线及历史日期批次缓存 1 分钟。K 线和分时都不随搜索候选或共享列表预取，只有当前展开面板按需读取；盘中分时仅在页面可见、面板展开且后端返回 `updating=true` 时每 30 秒刷新。进入多实例、多个热点股票同时动态更新或 WebSocket/SSE 推送层时再使用 Redis 共享最新行情和访问保护，并定义一致性、TTL、穿透/击穿/雪崩以及 Redis 或供应商不可用时不得把过期数据冒充实时行情的降级边界。Redis 不是业务事实源；股票标准身份、需要长期保留的行情事实和研究周期开始时的不可变报价快照仍由 MySQL 持久化。

## 7. 元数据策略：V1 采用轻量元数据

本项目需要保留“这条业务数据是谁、何时、从哪里产生的”等可追溯信息，但当前不需要建设 ERP 式通用元数据平台。

V1 的轻量元数据直接作为明确字段放在对应业务表或接口模型中，主要包括：

- 审计信息：只在业务能说明用途时保存创建人、创建时间、更新人或更新时间，不要求每张表机械具备全部字段；
- 行情信息：数据提供者、行情对应时间、系统拉取时间，以及真实、延迟、收盘或模拟等数据状态；
- 业务追溯信息：观点作者、研究周期、状态变化时间，以及确有业务需要时的操作来源。

这些字段仍属于强类型业务模型：Java 实体、DTO、数据库列和状态枚举都应有清楚含义，并由业务规则、数据库约束和测试保护。不能为了“灵活”把股票、观点、空间等核心字段塞进通用键值表。

V1 不建设以下完整元数据能力：

- 通用实体、字段、表单、页面和流程定义器；
- EAV（实体—属性—值）式通用字段存储；
- 根据数据库配置动态生成 CRUD、表单或审批流程；
- 允许运行时随意增加核心业务字段的低代码平台。

只有后续出现多个空间确实需要不同研究模板、字段经常变化且每次发版改表已经成为真实成本，或出现多个可配置工作流时，才在 V2 重新评估更完整的元数据方案。

## 8. 架构取舍

模块化单体见 [ADR-001](decisions/ADR-001-modular-monolith.md)，Longbridge sidecar 选择、代价和替换条件见 [ADR-002](decisions/ADR-002-longbridge-cli-sidecar.md)。
