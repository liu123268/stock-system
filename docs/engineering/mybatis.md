# MyBatis 与数据库访问复习

本文保存已讲专题的复习要点；学习进度与待回看点只见 [状态](../project/status.md#求职专题学习交接)。SQL 示例是教学简化，不是本轮执行记录。

## 1. Mapper 与执行链路

Controller 接请求，Service 处理业务，Mapper 访问数据库。MyBatis 创建 Mapper 代理，找到映射 SQL，经 JDBC 执行并转换结果。MyBatis-Plus 在此基础上提供 `BaseMapper` 通用 CRUD 和 Wrapper；继承接口不等于自己写了实现类。

项目入口：`UserService.findByUsername` → `AppUserMapper`；`AppUser` 用 `@TableName` 指定表，`@TableId` 指定主键。复杂或语义明确的操作可自定义注解 SQL，例如 `selectByIdForUpdate`。

## 2. 参数与结果映射

- `#{}` 生成占位符并绑定参数值；`${}` 直接替换文本，不可信输入会改变 SQL 结构。
- 列名、表名和排序方向不能用参数占位符绑定，动态选择须由后端白名单决定。
- 结果映射将列值转换为对象属性；名称对应可用自动映射、别名或显式 `resultMap`，类型转换由 TypeHandler 支持。
- Entity 表示表记录；多表查询可用专门结果对象；Service 再组装 ResponseVO，避免把内部字段直接暴露。
- 查询对象修改后必须显式更新。MyBatis-Plus 的字段更新策略可能忽略 null，不能把“未提交字段”误当“要求清空字段”；归档恢复案例见 [DB-04](../career/interview/03-database-persistence.md#db-04为什么对象字段设为-null数据库列却可能没有被清空)。

## 3. 可选条件与集合参数

Wrapper 是 MyBatis-Plus 提供的一类 Java 条件构造类，`LambdaQueryWrapper<AppUser>` 是其中一种；对象记录查询条件，构建它不执行查询，传给 Mapper 才执行。XML 是文本格式，不是框架；Mapper XML 是 MyBatis 读取的 SQL／结果映射配置文件。

同一个昵称与状态查询，下面是 Wrapper 和 XML 的对照（教学示例，当前项目未新增这段 XML）：

```java
LambdaQueryWrapper<AppUser> query = new LambdaQueryWrapper<>();
query.eq(AppUser::getNickname, "小明");
query.eq(AppUser::getStatus, "ACTIVE");
List<AppUser> users = appUserMapper.selectList(query);

// XML 方式在 AppUserMapper 中声明方法：
List<AppUser> selectByNicknameAndStatus(
    @Param("nickname") String nickname,
    @Param("status") String status
);
// 调用：appUserMapper.selectByNicknameAndStatus("小明", "ACTIVE");
```

```xml
<mapper namespace="com.stock.system.user.mapper.AppUserMapper">
    <select id="selectByNicknameAndStatus"
            resultType="com.stock.system.user.entity.AppUser">
        select * from app_user
        where nickname = #{nickname} and status = #{status}
    </select>
</mapper>
```

`namespace` 对应 Mapper 接口完整名称，`id` 对应方法名，`#{nickname}`／`#{status}` 对应 `@Param` 参数名；`resultType` 指定每行转换成 `AppUser`。两种写法的条件含义都是 `where nickname = ? and status = ?`，分别绑定“小明”和 `ACTIVE`。`query` 保存条件，`users` 保存结果；`AppUser::getNickname` 指定属性，后面的字符串提供比较值。

可选条件也能用 Java 的 if 控制：

```java
LambdaQueryWrapper<AppUser> query = new LambdaQueryWrapper<>();
if (status != null && !status.isBlank()) {
    query.eq(AppUser::getStatus, status);
}
List<AppUser> users = appUserMapper.selectList(query);
```

`eq(字段, 值)` 添加等值条件；`eq(布尔条件, 字段, 值)` 仅在条件成立时添加。上例是可选状态教学示例，项目现有用户名查询见 `UserService`。

MyBatis XML 的 `if` 按参数决定是否加入条件；`where` 在有条件时生成 where 并处理开头多余的 and/or；`foreach` 展开集合：

```xml
select id, nickname from app_user where id in
<foreach collection="userIds" item="userId"
         open="(" separator="," close=")">
    #{userId}
</foreach>
```

`foreach` 是 XML 循环标签；示例假设 Mapper 参数声明为 `@Param("userIds") List<Long> userIds`。`collection` 指定整个列表，`item` 给本轮元素起临时名字，`open`／`close` 生成括号，`separator` 生成元素间的逗号。列表 `[7, 9]` 生成 `in (?, ?)`，再绑定两个值；XML 标签由 MyBatis 处理，不能直接在 MySQL 执行。Wrapper 对应写法是 `query.in(AppUser::getId, userIds)`，再交给 Mapper 执行。空列表应按业务返回空结果或拒绝，不生成 `in ()`，也不随意丢掉限制条件。

选型：通用 CRUD 用 BaseMapper，简单单表筛选用 Wrapper，短而明确的自定义 SQL 可用注解，多表查询或复杂结果映射通常自己写 SQL 并放在 XML。简单查询可改写，但标准 Wrapper 不能直接替代任意 XML；自定义 SQL 也可与 Wrapper 配合。当前 `UserService.findByUsername` 用 Wrapper，`AppUserMapper.selectByIdForUpdate` 用注解，不把 XML 教学示例写成项目实现。面试练习见 [DB-14](../career/interview/03-database-persistence.md#db-14wrapper和mapper-xml是什么什么时候使用)。官方参考：[条件构造器](https://baomidou.com/guides/wrapper/)、[动态 SQL](https://mybatis.org/mybatis-3/dynamic-sql.html)。

## 4. 关联读取与 N+1

N+1 指先查询一次主体列表，再对 N 条记录各发一次关联查询；问题是数据库往返随列表长度增加，并非 Java 循环本身。

```sql
-- 逐条查询：第一次取观点，之后每条查询作者
select id, author_user_id, reason from stock_opinion
where research_cycle_id = ?;
select id, nickname from app_user where id = ?;

-- 关联查询：一次返回观点与作者
select o.id, o.reason, u.nickname
from stock_opinion o
join app_user u on u.id = o.author_user_id
where o.research_cycle_id = ?;

-- 批量方案：仍先查观点，再把去重后的作者ID一起查询
select id, nickname from app_user where id in (?, ?);
```

逐条方案取 N 条观点通常执行 1+N 次；批量方案先取观点，再取所有作者，通常两次，之后用 Java Map 按用户 ID 配对；内存配对不再访问数据库。关联查询内部仍需连接表、扫描与匹配，不能把一次 SQL 宣称为零成本。

`join` 只保留匹配行；`left join` 保留左表，未匹配右表字段为 null。左连接后在 where 中要求右表满足条件可能过滤未匹配行；条件应按业务决定放 on 还是 where。一对多连接会增加结果行数，分页与计数需明确主体。

当前项目时间线 SQL 位于 `StockResearchLifecycleService.TIMELINE_SQL`，通过 JDBC 查询并用 `toTimelineRow` 映射 Service 内部 `TimelineRow`，已关联观点、周期和作者。它不是当前 Mapper XML 或 `StockOpinionTimelineRow`；旧类名不能继续作为现实现入口。分页设计与证据见 [DB-08](../career/interview/03-database-persistence.md#db-08为什么研究时间线要先筛选再分页)。这里的单周期作者示例仅解释典型 N+1，不复刻旧实现。
