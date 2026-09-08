# 技术目标与选型参考

更新时间：2026-08-29

## 文档性质与维护规则

- 本文档保存用户的技术学习目标、能力覆盖要求和候选技术对照清单，属于项目规划输入，不是已经生效的系统架构。
- 本文档的“用户原始文字”来自用户上传的技术目标，具有最高参考价值，但不代表其中每项技术都必须进入主项目。
- 原始文字必须原样保留，AI 不得改写、删减或用总结覆盖。
- 场景触发和读取范围以 [规则路由](project-rules.md) 为准；原始条目是学习输入，不要求每次技术小改完整读全清单。
- 实际采用、计划、候选和不采用状态以 [项目实际技术栈](../architecture/technology-stack.md) 为准。
- 技术是否进入项目，必须经过“真实问题、候选方案、选择理由、实现成本、验证方法和退出方案”的选型过程。
- 后续分析只能追加或修改“项目应用分析”区域，不能修改“用户原始文字”。

## 用户原始文字

我的目标：2026 年找 Java 后端实习，并逐步发展到「Java 后端 + AI Agent 应用工程」。

学习原则：

【掌握】= 能独立使用、解释为什么这么用、知道常见坑，并在项目中真正使用过。

【理解】= 能解释核心原理、回答常见面试追问，不要求源码级掌握。

不要为了堆技术栈强行使用技术；只有出现真实业务问题时再引入，并让我理解“问题 → 方案 → 原理 → 实现 → 坑 → 替代方案”。

====================
一、Java 核心【重点掌握】
===============

1. Java 基础

* 面向对象
* 泛型
* 异常
* Lambda / Stream
* equals / hashCode
* IO / NIO 基础
* 序列化 / JSON 基础

2. 集合【重点】

* ArrayList
* HashMap
* HashSet
* Queue / Deque
* ConcurrentHashMap

需要理解：
底层结构、扩容、Hash 冲突、线程安全、不同集合适用场景。

3. 并发 / JUC【重点】

* Thread / Runnable / Callable
* synchronized
* volatile
* JMM
* CAS / Atomic
* ReentrantLock
* ThreadLocal
* ThreadPoolExecutor
* CompletableFuture
* ConcurrentHashMap

重点掌握：
线程安全、线程池参数与执行流程、拒绝策略、并发任务设计、异常处理。

AQS 理解思想即可，不读源码。

4. JVM【理解核心】

* JVM 内存区域
* 对象创建
* 类加载
* GC 基本机制
* GC Roots
* OOM / StackOverflow
* 常见 JVM 问题排查

G1 / ZGC 了解即可。
不要求高级 JVM 调优和 GC 源码。

5. 反射 / 注解 / 动态代理【理解】

* Class / Method / Field
* Annotation
* 反射调用
* JDK / CGLIB 动态代理

重点理解：
Spring IoC、AOP、@Transactional 为什么能工作。

不需要深入反射源码。

====================
二、MySQL【最高优先级】
==============

不仅要会 SQL，还要会数据库设计。

1. 数据库设计

* 表结构设计
* 主键
* 唯一约束
* 字段类型选择
* 一对一 / 一对多 / 多对多
* 索引设计
* 分页
* 批量操作

2. SQL

* CRUD
* Join
* 子查询
* 聚合
* Explain
* 慢 SQL 分析

3. 索引

* InnoDB
* B+Tree
* 聚簇索引 / 二级索引
* 联合索引
* 最左匹配
* 覆盖索引
* 回表
* 索引失效

4. 事务和锁

* ACID
* 隔离级别
* MVCC
* 行锁
* 间隙锁
* Next-Key Lock
* 乐观锁 / 悲观锁
* 死锁基本分析

5. 日志【理解】

* redo log
* undo log
* binlog

要求项目中真正做：
表设计 + 索引设计 + Explain + 一次 SQL 优化。

====================
三、Redis【重点掌握】
=============

* String / Hash / List / Set / ZSet
* TTL
* 缓存设计
* Session
* 排行榜 / 计数等常见场景

重点：

* 缓存穿透
* 缓存击穿
* 缓存雪崩
* 热点 Key
* Big Key
* 数据库与缓存一致性
* 分布式锁
* Redis 原子操作 / Lua 基础
* Redisson 实际使用
* RDB / AOF
* 内存淘汰策略

不能只会 RedisTemplate。

====================
四、Spring 核心【掌握】
===============

在会 Spring Boot CRUD 的基础上继续：

* IoC / DI
* Bean 生命周期
* AOP
* 动态代理
* @Transactional 原理和失效场景
* Spring MVC 请求流程
* 参数校验
* 全局异常处理
* Filter / Interceptor
* Spring Security 基础
* JWT
* RBAC 权限模型
* OAuth2 基本思想

MyBatis：
会 SQL 映射、动态 SQL、事务和性能问题。

不要依赖 MyBatis-Plus 导致自己不会 SQL。

不要求读 Spring / MyBatis 源码。

====================
五、MQ【掌握一种】
==========

Kafka 或 RocketMQ 选择一种真正学会。

理解：

* Producer / Consumer
* Topic
* Consumer Group
* 异步
* 解耦
* 削峰
* 消息可靠性
* 消息丢失
* 重复消费
* 消费幂等
* 顺序消息
* 重试
* 死信
* 消息积压

项目中必须有一个真实异步业务场景。

====================
六、网络 + 操作系统【面试基础】
=================

网络：

* TCP
* HTTP / HTTPS
* DNS
* Cookie / Session
* JWT
* REST
* SSE
* WebSocket
* 长连接
* 超时 / 重试

操作系统：

* 进程 / 线程
* 用户态 / 内核态
* 上下文切换
* IO
* 内存
* 锁
* 死锁

数据结构算法持续练：
数组、链表、Hash、栈、队列、树、堆、二分、常见排序等。

持续刷 Hot100，不走竞赛路线。

====================
七、工程能力【必须】
==========

* Git
* Maven
* Linux
* Shell 基础
* Docker
* Nginx 基础
* RESTful API 设计
* 接口幂等
* 日志
* 配置管理
* 异常处理
* 性能排查

测试：

* JUnit
* Mockito
* 单元测试
* 基础集成测试

可观测性：

* 日志
* Metrics
* Tracing
* Micrometer 基础
* Prometheus / Grafana 了解或实践一次

CI/CD：
理解流程，GitHub Actions / GitLab CI 等会一种简单使用即可。

====================
八、微服务 / 分布式【理解 + 简单实践】
======================

需要知道：

* RPC 是什么
* HTTP / RPC 的区别
* 服务注册发现
* 配置中心
* API Gateway
* 负载均衡
* 超时
* 重试
* 限流
* 熔断
* 降级
* 分布式 ID
* 分布式事务基本思想
* 最终一致性
* 链路追踪

Spring Cloud / Spring Cloud Alibaba：
做过简单实践即可。

Nacos / Gateway / Sentinel：
知道解决什么问题，会基础使用。

Dubbo / gRPC：
理解 RPC 思想，项目需要时选择一个使用。

当前不需要深入：
K8s、Service Mesh、复杂分库分表、大规模分布式架构。

====================
九、可选中间件
====================

Elasticsearch：

如果项目有搜索 / RAG / 日志搜索，再学习：

* 倒排索引
* Mapping
* Query
* 分词
* 基本搜索优化

不是所有 Java 实习都必须会，不为了简历强行加。

====================
十、设计模式
======

不要背 23 种。

项目中重点理解：

* 策略
* 工厂
* 模板方法
* 责任链
* 观察者
* 适配器

遇到真实业务问题再使用。

====================
以下是 AI Agent 增量能力
=================

Java 后端基础不能被 Agent 技术替代，而是在后端基础之上增加以下能力。

====================
十一、LLM 应用基础【掌握】
===============

* 主流模型 API 调用
* Chat / Message 模型
* System Prompt
* Token
* Context Window
* Temperature 等基础参数
* Structured Output
* JSON Schema
* 流式响应 SSE
* 超时
* 重试
* 限流
* 并发
* Token / 成本统计
* 模型切换 / Model Routing 基础

重点学习 Context Engineering，而不只是“写 Prompt”。

理解：
应该把什么信息放进上下文、什么时候压缩、什么时候检索、什么时候调用工具。

====================
十二、Tool Calling + Agent【重点掌握】
=============================

必须理解：

* Function / Tool Calling
* Tool Schema
* 参数校验
* Tool 返回结果
* Agent Loop
* ReAct 基本思想
* 任务拆解
* Workflow 和 Agent 的区别
* 状态机
* Agent 状态管理
* 超时 / 重试
* Tool 失败处理

必须真正实现：

用户
→ LLM
→ 选择 Tool
→ Java Service
→ 数据库 / Redis / 外部 API
→ Tool Result
→ LLM
→ 最终结果

不能停留在 Demo。

====================
十三、Memory / 上下文管理【掌握】
=====================

理解：

* Conversation History
* Short-term Memory
* Long-term Memory
* 摘要压缩
* Context 截断
* Token 控制
* Memory 持久化
* 用户级 / 会话级隔离

项目中真正实现会话状态管理。

====================
十四、RAG【重点掌握】
============

完整理解：

文档
→ Parse
→ Chunk
→ Embedding
→ Vector Store
→ Retrieval
→ Rerank
→ Context
→ LLM

掌握：

* 文档解析
* Chunk 策略
* Embedding
* 向量相似度
* TopK
* Metadata Filter
* Dense Retrieval
* Keyword Retrieval
* Hybrid Search
* Rerank
* 引用来源
* 幻觉控制
* RAG Evaluation

向量数据库只掌握一种即可：

Milvus / Qdrant / pgvector / Elasticsearch Vector 等。

不能只会调用框架的 `vectorStore.similaritySearch()`，必须知道检索链路为什么这么设计。

====================
十五、MCP【掌握使用 + 理解】
=================

理解：

* MCP Client
* MCP Server
* Tool
* Resource
* Prompt
* Tool discovery
* 远程工具调用

自己实现或接入至少一个 MCP Server。

能解释：

MCP 和普通 REST API 的区别；
MCP 和 Function Calling 的关系。

====================
十六、Java AI 框架【掌握一个】
===================

Java 路线优先：

Spring AI

重点学习：

* ChatModel / ChatClient
* Structured Output
* Tool Calling
* Memory
* RAG
* Vector Store
* MCP
* Advisors
* Streaming
* Observability

LangChain4j 可以作为第二选择。

不要同时花大量时间学习 Spring AI、LangChain4j、Dify、Coze 等所有框架。

框架只是实现工具，核心是理解 Agent / RAG / Tool 的原理。

====================
十七、Agent 工程化【非常重要】
==================

生产级 Agent 重点学习：

可靠性：

* Timeout
* Retry
* Circuit Breaker
* Rate Limit
* Fallback
* Idempotency

并发：

* ThreadPool
* CompletableFuture
* 模型调用并发控制

状态：

* MySQL
* Redis
* MQ
* 任务状态机

可观测性：

* Prompt
* Model
* Tool Call
* Latency
* Token
* Cost
* Error
* Trace

必须能够定位：
“Agent 为什么这一次失败了？”

====================
十八、Agent Evaluation【重要】
=======================

不要只判断 Demo “看起来不错”。

需要学习：

* 测试数据集
* Golden Dataset
* 自动评测
* 人工评测
* 回归测试
* Tool Call 成功率
* Task Success Rate
* RAG 检索效果
* 延迟
* Token
* Cost

做到修改 Prompt / Model / RAG 后，可以判断效果到底变好了还是变差了。

====================
十九、Agent 安全【重要】
===============

至少理解：

* Prompt Injection
* Tool Injection
* 越权调用
* Tool 权限控制
* 参数校验
* 敏感数据
* Secret / API Key 管理
* 用户数据隔离
* 输出校验
* 高风险操作人工确认（Human-in-the-loop）

LLM 不能直接获得数据库和系统的无限权限。

====================
二十、Agent 后学内容
=============

等单 Agent + Tool + RAG + Memory + MCP + Evaluation 做扎实之后，再学习：

* Multi-Agent
* Agent Planning 高级策略
* Reflection
* Deep Research
* A2A
* Graph RAG
* 长期记忆高级方案
* Agent 自主规划

这些不是当前 Java 实习准备的第一优先级。

====================
二十一、Python【加分项】
===============

如果走 AI Agent：

Java 仍然作为主语言。

但需要具备基础 Python 能力：

* 能读代码
* 能修改脚本
* requests / FastAPI 基础
* 能调用 Python AI 服务

不需要因为 Agent 转成 Python 主力，更不需要现在学习 PyTorch、模型训练、CUDA。

除非以后目标变成“大模型算法 / Agent 算法岗”。

====================
暂时不要深挖
======

* Spring 源码
* MyBatis 源码
* Netty 源码
* Redis 源码
* Kafka / RocketMQ 源码
* AQS 源码
* JVM 高级调优
* K8s 深入
* Service Mesh
* 复杂分库分表
* 自己造 RPC 框架
* 23 种设计模式全部背
* Transformer 数学推导
* PyTorch / 模型训练
* Multi-Agent 炫技

====================
最终能力标准
======

任何核心技术我都应该能回答：

1. 它解决什么问题？
2. 为什么这个场景需要它？
3. 不使用它会怎样？
4. 核心原理是什么？
5. 项目里我是怎么实现的？
6. 会遇到哪些坑？
7. 有没有替代方案？
8. 如何测试它确实有效？

以后你作为我的 Coding Agent：

不要只帮我生成代码。

发现项目存在适合学习上述知识的真实场景时，先指出这个问题和对应知识点，再带我完成实现。

代码写完后必须让我能够解释它。

尤其禁止：
为了让项目“看起来高级”而强行加入 Redis、MQ、并发、微服务、设计模式或 Agent 技术。

## 项目应用分析

### 当前已经覆盖

当前采用状态与证据只见 [实际技术栈](../architecture/technology-stack.md)，业务完成度见 [状态](status.md)。不在原始目标旁复制另一份进度快照。

### 主项目必须继续补强

- Java 集合、并发/JUC、JVM、反射、注解和动态代理的项目结合与面试解释；
- MySQL 联合索引、覆盖索引、分页、事务隔离、MVCC、锁、死锁、Explain 和一次真实 SQL 优化；
- Redis 数据结构、缓存一致性、穿透/击穿/雪崩、原子操作、Lua、Redisson 和持久化边界；
- Spring IoC、Bean 生命周期、AOP、`@Transactional` 原理和失效场景、MVC 请求流程；
- 一种 MQ 的真实异步场景、可靠性、幂等、重试、死信和积压处理；
- 接口幂等、超时、重试、限流、日志、配置管理和性能排查；
- Docker、Nginx、CI/CD、Metrics、Tracing 和一次 Prometheus/Grafana 实践；
- 网络、操作系统和数据结构算法作为独立持续学习主线。

### Agent 部分的初步定位

- Agent 不能替代 Java 后端主线，安排在空间、行情、自选股和观点时间线完成之后；
- Agent 的具体顺序以 [路线第 6 阶段](roadmap.md#阶段-6java-agent-增量) 为准，先验证结构化模型调用，再形成 Java Tool Calling 闭环；
- 适合本项目的工具包括：查询空间自选股、获取最新可用行情、查询历史行情、读取成员观点和生成带来源的研究摘要；
- Agent 只做信息整理、观点对比、风险清单和待验证问题，不做自动荐股、自动交易或收益承诺；
- Java 框架优先评估 Spring AI，LangChain4j 只作为备选，不同时重投入；
- Tool Calling 稳定后再逐步加入会话状态、Memory、RAG、MCP、Observability、Evaluation 和安全控制；
- 每一步都需要超时、重试、权限、参数校验、成本统计、测试集和回归证据，不能停留在聊天 Demo。

### 不直接塞进主项目的内容

- Spring Cloud Alibaba、Nacos、Gateway、Sentinel、Dubbo/gRPC 可以做小型独立实践；主项目出现真实服务边界前不拆微服务；
- Elasticsearch 只有在研究文档检索、RAG 或日志搜索形成真实需求后再评估；
- K8s、Service Mesh、复杂分库分表、源码深挖、模型训练和 Multi-Agent 当前不是优先级。
