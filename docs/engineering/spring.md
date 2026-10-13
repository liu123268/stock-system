# Spring 核心与 Web 复习

本文维护 Spring 专题已讲内容的复习要点与项目代码入口；当前学习结果、卡点和下一动作见 [学习交接](../project/status.md#求职专题学习交接)，完整教学范围见 [路线第 3 块](../project/roadmap.md#3-spring-核心与-web-开发)。

## 1. 从登录链路理解对象管理与注入

Controller 调用 Service，必须先取得 Service 对象。构造器声明依赖，创建该组件的一方提供依赖，组件再把引用保存到字段中。

| 概念 | 对应的具体行为 |
| --- | --- |
| Bean | Spring 容器管理的对象；类是对象的定义，类名或注解本身不是对象 |
| IoC，控制反转 | 把对象创建、依赖组织和生命周期管理交给容器；应用通过配置声明需要哪些组件 |
| DI，依赖注入 | 从外部提供组件需要的对象；本项目使用构造器接收。手动创建对象再传入构造器也是 DI，当前由 Spring 自动完成 |

项目密码校验沿三个位置串起，不能只看注解：

1. [PasswordConfig.passwordEncoder](../../src/main/java/com/stock/system/config/PasswordConfig.java) 创建并返回 `BCryptPasswordEncoder`。Spring 处理这份配置，按需调用工厂方法准备编码器 Bean。
2. [AuthService 构造器](../../src/main/java/com/stock/system/auth/service/AuthService.java) 的 `PasswordEncoder passwordEncoder` 参数接收容器提供的引用；`this.passwordEncoder = passwordEncoder` 把引用保存到字段，不创建新对象。
3. 同一 Service 的 `login` 通过 `passwordEncoder.matches(request.password(), user.getPasswordHash())` 使用编码器。构造器在装配组件时执行，登录方法在处理业务调用时执行。

`PasswordConfig` 不需要写 `AuthService` 或主动调用它的构造器。依赖匹配与提供由 Spring 负责；当前编码器的声明类型是接口，实际对象是 BCrypt 实现。`AuthController` 同样通过 [构造器](../../src/main/java/com/stock/system/auth/controller/AuthController.java) 接收 `AuthService`。单构造器组件可省略构造器上的 `@Autowired`，见 [官方构造器注入说明](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html)。

## 2. 配置类与方法各声明什么

`@Configuration` 标记类为配置来源，并使它成为组件扫描候选；`@Bean` 标记某个方法提供需要注册的对象。先把类纳入配置处理，再读取其中的 Bean 定义。

```java
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

这里涉及两个对象：`PasswordConfig` 对象用于提供配置方法；方法返回的 `BCryptPasswordEncoder` 对象用于密码编码与校验。类被识别，不表示其中所有普通方法的返回值都会注册为 Bean。

下表假定采用默认组件扫描、类位于扫描范围内，且没有额外导入或手动注册：

| 类上的标记 | 方法上的标记 | 结果 |
| --- | --- | --- |
| `@Configuration` | `@Bean` | 配置类被识别，方法提供的编码器注册为 Bean |
| `@Configuration` | 无 `@Bean` | 配置类被识别，这个普通方法的返回值不会自动注册 |
| 无组件标记 | `@Bean` | 扫描不会仅凭方法上的注解选中这个普通类，其 Bean 方法也不会被处理 |
| `@Component` | `@Bean` | 组件类被识别，方法提供的编码器也能注册为 Bean |

因此 `@Bean` 不强制搭配 `@Configuration`，其所在类也可由组件扫描、`@Import` 或显式注册等方式纳入配置处理。仅有 `@Configuration` 也可以通过 `@Import`、`@ComponentScan` 组织其他配置；如果它只剩一个没有 `@Bean` 的普通方法，就不再自动提供该方法返回的编码器。

这是 Spring 对配置加载与 Bean 声明的分工，不是 Java 语言要求两个注解必须同时存在。依据：[配置类与组件扫描](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Configuration.html)、[Bean 方法声明](https://docs.spring.io/spring-framework/reference/core/beans/java/bean-annotation.html)。

## 3. 配置类增强：再次调用方法是否创建第二个对象

以下是机制教学示例，不是项目业务源码。假设 `LoginHelper` 的构造器接收并保存编码器。两项 Bean 均采用默认单例作用域，方法为普通实例方法：

```java
@Configuration
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public LoginHelper loginHelper() {
        return new LoginHelper(passwordEncoder());
    }
}
```

关键在 `new LoginHelper(passwordEncoder())`：先取得内部方法调用的返回值，再把该值传给构造器。假设容器已通过 `passwordEncoder()` 创建编码器 A，此时再执行 `loginHelper()`：

| 配置方式 | 内部调用 `passwordEncoder()` 的行为 | Helper 保存的对象 |
| --- | --- | --- |
| 默认 `@Configuration`，即 `proxyBeanMethods = true` | Spring 增强配置类，拦截调用并取得容器管理的编码器 | 已有的 A |
| `@Component`，或 `@Configuration(proxyBeanMethods = false)` | 普通 Java 方法调用，再执行方法体中的 `new` | 新建的 B |

第二种情况下，容器登记的编码器 Bean 仍然是 A；B 是内部普通调用额外创建的对象，没有作为该编码器 Bean 注册。A 与 B 的类型相同，对象不同。这个例子中的增强作用是让方法之间的调用取得受管理的 Bean，避免直接调用工厂方法而创建额外对象；不是说所有对象都只能创建一次。该推演按默认单例说明，实际创建顺序无需固定为示例顺序。

当前项目的 `PasswordConfig` 只有一个 Bean 方法，没有上述方法之间的调用；A／B 对照用于理解机制，不作为项目故障或运行验证证据。依据：[Spring 完整配置与 lite 模式说明](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html)。

## 4. 用参数注入表达 Bean 之间的依赖

同一教学示例可改为：

```java
@Bean
public LoginHelper loginHelper(PasswordEncoder encoder) {
    return new LoginHelper(encoder);
}
```

Spring 从容器中解析 `PasswordEncoder` 并传给方法参数；方法直接使用该引用，不再调用另一个工厂方法。这种写法在普通组件类或关闭配置类方法代理时也可使用。依据：[Bean 方法参数与依赖](https://docs.spring.io/spring-framework/reference/core/beans/java/bean-annotation.html#beans-java-dependencies)。

排错先区分阶段：普通 Java 编译检查类型与语法；Spring 装配检查必需依赖是否可获得。当前 Service 的编码器 Bean 若缺失，会阻止其非懒加载装配，尚未到执行登录 SQL 的阶段。业务层自行创建编码器则会绕开本次容器提供的配置／测试替身；`@Bean` 工厂方法中的 `new` 是向容器提供对象，二者要按对象最后由谁管理分别判断。

## 5. 默认单例与请求数据

Spring 默认作用域为 `singleton`：同一个容器中，同一个 Bean 定义对应一个共享实例。当前 AuthService 和密码编码器未指定其他作用域，多个请求会复用这些组件；单例不表示整个 JVM 中某个类只能存在一个对象，也不表示每次 HTTP 请求都会重新创建组件。依据：[Bean 作用域与单例边界](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html)。

单例不会自动把方法调用串行化，多个请求可以同时进入同一个 Service。项目 [AuthService.login](../../src/main/java/com/stock/system/auth/service/AuthService.java) 使用方法局部变量 `AppUser user = userService.findByUsername(username)` 保存本次查询结果：每次调用有自己的局部变量引用，另一调用不会通过给自己的变量赋值来覆盖它。局部变量不保证其指向的任意可变对象都线程安全；共享依赖、缓存等对象仍需按自身并发规则使用。

如果把本次用户改存为单例 Service 的 `private AppUser currentUser` 字段，会让多个请求写同一个位置。以下为未落地的教学反例，假设查询均成功：

```java
private AppUser currentUser;

public Long queryUserId(String username) {
    this.currentUser = userService.findByUsername(username);
    return this.currentUser.getId();
}
```

请求 A 写入用户甲后暂停，请求 B 写入用户乙，A 恢复读取字段时就可能拿到乙的 ID；返回前清空字段不能阻止之前的覆盖，还可能影响另一个调用。正式登录方法没有这个共享用户字段。请求数据优先使用参数、局部变量和返回值传递；确需共享的状态另行设计并发与身份隔离。`final` 只限制字段引用重新赋值，不自动保证所引用对象线程安全。
