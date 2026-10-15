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

Bean 是 Spring 对受其管理的 Java 对象的称呼；`@Bean` 是写在方法上的注解，声明把该方法提供的对象注册为 Bean，因此沿用同一个名称。注解本身、提供对象的方法、方法返回的对象分别识别：本例成为编码器 Bean 的是返回的 `BCryptPasswordEncoder` 对象。Bean 不要求都用 `@Bean` 声明；项目的 AuthService 经扫描识别 `@Service` 后，其受管理的实例同样是 Bean。查询得到的 AppUser 是业务数据对象，不因为出现在 Spring 项目中就自动成为 Bean。

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

单例没有默认的按分钟过期或闲置失效时间。对象创建后，通常由容器保存到该容器关闭；在通常的 Spring Boot 运行方式中，对应这次后端运行期间。后端持续运行三天，期间获取的仍是该实例；停止后重新启动，新容器会创建新实例。这里的“取”是取得引用来使用对象，容器仍保存着它。依据：[容器关闭与生命周期回调](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-shutdown)。

单例不会自动把方法调用串行化，多个请求可以同时进入同一个 Service。项目 [AuthService.login](../../src/main/java/com/stock/system/auth/service/AuthService.java) 使用方法局部变量 `AppUser user = userService.findByUsername(username)` 保存本次查询结果：每次调用有自己的局部变量引用，另一调用不会通过给自己的变量赋值来覆盖它。局部变量不保证其指向的任意可变对象都线程安全；共享依赖、缓存等对象仍需按自身并发规则使用。

如果把本次用户改存为单例 Service 的 `private AppUser currentUser` 字段，会让多个请求写同一个位置。以下为未落地的教学反例，假设查询均成功：

```java
private AppUser currentUser;

public Long queryUserId(String username) {
    this.currentUser = userService.findByUsername(username);
    return this.currentUser.getId();
}
```

请求 A 写入用户甲后暂停，请求 B 写入用户乙，A 恢复读取字段时就可能拿到乙的 ID；这是同一对象的共享字段被覆盖，与反复 `new` 出多个对象是不同问题。返回前清空字段不能阻止之前的覆盖，还可能影响另一个调用。正式登录方法没有这个共享用户字段。请求数据优先使用参数、局部变量和返回值传递；确需共享的状态另行设计并发与身份隔离。`final` 只限制字段引用重新赋值，不自动保证所引用对象线程安全。

读代码时先找声明所在的括号层级：直接在类体中声明的是字段，方法体内声明的是局部变量，方法／构造器参数列表中声明的是参数。变量名称相同不表示作用域相同；`private` 也不是识别字段的必要条件。

| 代码与位置 | 读法 |
| --- | --- |
| 类体中的 `private AppUser currentUser;` | 声明一个属于对象的字段；这里只声明，尚未查询用户 |
| 方法中的 `this.currentUser = userService.findByUsername(username);` | 查询并给已有字段赋值；`this` 指当前 Service 对象，不是新声明局部变量 |
| 方法中的 `AppUser currentUser = userService.findByUsername(username);` | 声明本次调用的局部变量，再保存查询返回的引用；这是删除上述同名字段后的教学改写 |
| 方法中的 `Long userId = currentUser.getId();` | 声明局部变量 userId，保存读取的 ID；读取来源是否共享，要继续看 currentUser 的声明位置 |

记代码时以“声明位置 → 所属对象或调用 → 使用／赋值位置”为线索，不把 `this` 当成锁或把类型名当成创建对象的语句。

## 6. prototype：获取对象与调用方法

`prototype` 表示每次容器提供该 Bean 时创建一个新实例，包括一次直接 `getBean` 请求或一次普通依赖装配；不表示每次声明变量、调用业务方法或收到 HTTP 请求都会创建实例。以下为教学配置，正式 PasswordConfig 未修改：

```java
@Configuration
public class PasswordConfig {
    @Bean
    @Scope("prototype")
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

`Scope` 来自 `org.springframework.context.annotation.Scope`。下面的教学调用仅加载这份配置，容器类型为 `AnnotationConfigApplicationContext`；外层 try 负责结束时关闭容器：

```java
try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(PasswordConfig.class)) {
    PasswordEncoder first = context.getBean(PasswordEncoder.class);
    PasswordEncoder second = context.getBean(PasswordEncoder.class);
    System.out.println(first == second);
}
```

`getBean` 请求容器提供对象，`PasswordEncoder.class` 指定要查找的类型；`==` 在这里判断引用是否指向同一个对象。按作用域规则推演，prototype 下上述两次获取得到不同实例，输出 false；删除 @Scope、恢复默认 singleton 后输出 true。本节未运行此示例，不把推演写成验证结果。若第二行改为 `PasswordEncoder second = first`，只是让另一个变量指向已有实例，不增加容器获取请求。

普通构造器注入将依赖解析发生在创建接收方时。单例 AuthService 将编码器保存到字段后，后续 `matches`／`encode` 是使用已有引用；即使假设该编码器配置为 prototype，也不会在每次密码操作时自动更换实例。本例不配置作用域代理或动态获取。依据：[prototype 与注入单例的边界](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html#beans-factory-scopes-sing-prot)。`request` 则按单个 HTTP 请求限定实例范围，依赖 Web 容器，不能将 prototype 等同于 request。

作用域的实际取舍是实例复用、字段归属与创建成本。singleton 适合可长期复用的业务服务；prototype 每次获取都需要准备新实例，增加对象创建、内存分配和后续回收工作，实际成本取决于获取次数和初始化开销，不能据此宣称一定很慢。“重走一遍”指这个对象的创建、依赖注入和初始化，不是重新启动系统或执行完整业务流程。

prototype 的知识准备场景：对象代表一次独立的报表生成任务，字段保存该任务的 `progress`、`outputPath`。每个任务分别获取新实例，就各有一份字段；若两个请求确实使用不同 Service 实例，也不会通过各自的 `currentUser` 字段互相覆盖。它并不自动保证每次 HTTP 请求拿到不同 Service，仍按上面的实际获取／注入时机判断。也可采用“单例报表服务＋每次独立创建的任务数据对象”，不必强制使用 prototype。当前登录继续使用单例 Service 和方法局部变量；报表任务仅为用途示例，不作为项目实现记录。

## 7. request：一个 HTTP 请求内复用对象

先区分两个动作：HTTP 请求是浏览器或其他客户端访问后端，例如一次 `POST /api/auth/login`；获取 Bean 则是由 Spring 提供一个受管理的对象，例如启动装配时把 AuthService 传入 Controller 构造器。当前登录处理使用 Controller 已保存的 `authService` 调用方法，不会因收到一次 HTTP 请求而自动再执行一次 `getBean`。此前 `getBean` 对照是用于说明对象提供规则的教学示例。

`request` 作用域按 HTTP 请求划分 Bean 的实际业务实例：本次请求需要该 Bean 时创建，同一请求内复用它，另一个请求需要时创建另一个实例。同一个人再次调用接口也是另一个请求；一个请求可以经过多个方法调用。

| 作用域 | Spring 提供的实际业务对象如何复用 |
| --- | --- |
| singleton | 同一容器中，同一个 Bean 定义复用同一个对象 |
| prototype | 每次向容器获取该 Bean 时创建新实例；使用已经取得的引用不算再次获取 |
| request | 同一个 HTTP 请求内复用，不同 HTTP 请求分别提供实例 |

`@RequestScope` 来自 `org.springframework.web.context.annotation.RequestScope`。以下只示意把已有 Service 改为请求作用域的类注解，不是项目实现；当前 AuthService 保持默认单例，Java 源码未修改：

```java
@Service
@RequestScope
public class AuthService {
    // 其余业务代码沿用原实现
}
```

后续代理专题的边界：`@Service` 将类作为业务组件注册，`@RequestScope` 指定其请求作用域，并默认使用类代理。单例 Controller 注入的是可定位当前请求目标的代理，业务调用会交给当前请求对应的真实 Service；不能通过注入代理引用的 `==` 判断不同请求的真实目标是否相同。当前基础理解先区分 HTTP 请求与 Bean 获取，代理机制沿后续专题展开。

请求作用域 Bean 在一次请求内跨多个方法复用；方法局部变量则属于一次方法调用，两者的保存范围不同。当前登录的用户查询结果只需随参数、局部变量和返回值传递，现有单例 Service 的写法已满足这一点；请求作用域是知识准备，不是当前登录的必需改造。请求完成后，该请求的 Bean 不用于后续请求；作用域结束与 JVM 内存回收分别判断。依据：[请求作用域](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html#beans-factory-scopes-request)、[RequestScope 注解与默认代理](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/context/annotation/RequestScope.html)。

## 8. Bean 生命周期：准备、使用与结束

先按当前默认、非懒加载的单例组件理解基础流程：准备依赖 → 创建并注入 → 初始化 → 供业务使用 → 容器正常关闭时执行已配置的清理回调。构造器注入在创建对象时完成，不能机械理解为所有对象都先创建完、再另行调用构造器注入；框架扩展点与代理处理沿后续专题展开。

项目先准备 AuthService 所需的编码器等依赖，再把它们作为构造器参数传入。`PasswordConfig.passwordEncoder()` 中的 `new BCryptPasswordEncoder()` 创建编码器；AuthService 构造器中的 `this.passwordEncoder = passwordEncoder` 保存已经提供的依赖。当前组件通常在应用启动装配时准备，HTTP 登录请求到来后执行 `login()` 使用现有对象。具体 Bean 创建顺序按依赖关系判断，不是按源码文件排列。

| 代码位置或标记 | 执行时机与用途 |
| --- | --- |
| 构造器 | 创建对象时执行；当前项目通过参数接收并保存必需依赖 |
| `@PostConstruct` 方法 | 依赖注入完成后进行初始化，例如检查配置、准备内部数据；默认单例的一次创建过程中执行，不随每次 HTTP 请求重跑 |
| 普通业务方法，例如 `login()` | 调用方需要时调用，一个对象可以执行多次 |
| `@PreDestroy` 方法 | 受管理 Bean 结束生命周期时进行清理；当前单例通常随容器正常关闭执行，强制终止进程不能保证调用 |

两个生命周期注解来自 `jakarta.annotation`，由 Spring 识别并调用。方法只命名为 `init` 或 `close`，本身不等于已经声明这类注解回调；还有其他显式配置方式。初始化与销毁回调按实际需求声明，AuthService 当前没有这两个注解，也不需要为了教学添加空方法。

真实清理入口见 [LongbridgeSdkClient.close](../../src/main/java/com/stock/system/stock/market/LongbridgeSdkClient.java)：SDK 模式启用这份 Bean 时，Spring 容器正常关闭会调用其 `@PreDestroy` 方法。一次行情查询正常完成、浏览器关闭页面或用户退出系统登录，不会因此触发这个销毁回调。方法中的 `closed = true` 标记客户端不再接受使用，`discardSession()` 主动释放已有 SDK Session。

SDK 是 Java 进程内部使用的库，不是另一个独立运行的程序。Java 进程真正结束后，内部 SDK 也停止运行，操作系统回收该进程持有的本地资源。清理方法的意义发生在退出之前：收到停止通知 → Java 进程仍在运行 → Spring 关闭容器并调用清理方法 → 收尾完成后进程结束。操作系统回收资源，不等于替程序完成业务收尾；例如仍留在程序内存中的待写数据，不会仅因进程结束就自动写入文件。Bean 清理回调与 JVM 回收对象内存也分别判断。依据：[Java 退出与关闭钩子](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Runtime.html#addShutdownHook(java.lang.Thread))。

Spring 容器还可以单独关闭，所在的 Java 进程继续执行其他代码，此时其中 Bean 持有的资源仍应主动释放。另一个不同场景是查询超时或部分连接异常：当前 SDK 代码可能只丢弃旧 Session，保留客户端 Bean，下次查询再创建 Session；这不是执行客户端的销毁回调。以上用于理解资源所有者如何结束使用，不修改行情业务。

当前必需依赖无法提供，或初始化回调抛出异常，都可能阻止 Bean 创建并导致启动失败；一次登录密码错误属于使用阶段的业务失败，一般不会因此销毁单例 AuthService。以上是机制与源码分析，未新增运行实验。依据：[生命周期回调](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html)、[PostConstruct 与 PreDestroy](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/postconstruct-and-predestroy-annotations.html)。

## 9. Spring Boot 启动与组件扫描

项目入口是 [StockSystemApplication](../../src/main/java/com/stock/system/StockSystemApplication.java)，位于 `com.stock.system`。`main` 中的 `SpringApplication.run(StockSystemApplication.class, args)` 以该类组织应用启动、配置加载与容器准备，在当前 Web 项目中启动内嵌服务器；`.class` 提供启动配置类的信息，`args` 是 main 收到的命令行参数。这里说明职责，不按这句话推定完整框架内部调用顺序。

`@SpringBootApplication` 组合三项职责：

| 相关注解 | 职责 |
| --- | --- |
| `@SpringBootConfiguration` | 将主类作为 Boot 应用的配置来源，具备配置类能力 |
| `@ComponentScan` | 默认扫描主类所在包及其子包，发现应用组件 |
| `@EnableAutoConfiguration` | 根据可用依赖、配置属性和已存在的 Bean 等条件，启用框架提供的自动配置 |

当前 AuthController 位于 `com.stock.system.auth.controller`，AuthService 位于 `com.stock.system.auth.service`，PasswordConfig 位于 `com.stock.system.config`，都处于默认组件扫描范围。扫描识别 Controller、Service 与配置类，再处理配置类里的 Bean 方法，按依赖关系装配对象。`@Bean` 方法本身不能让扫描范围之外的普通类自动成为配置来源；配置也可以通过显式导入或注册加载。

组件扫描用于发现应用写的组件，自动配置用于按条件提供框架能力。项目已有 `spring-boot-starter-webmvc` 依赖，Boot 可以据此及其他条件配置 MVC 等 Web 基础能力；引入依赖不等于全部相关自动配置必然生效，仍需看配置、已有 Bean 和条件。

启动类还显式使用 `@MapperScan` 发现 MyBatis Mapper 接口，并排除 `UserDetailsServiceAutoConfiguration`，分别属于 Mapper 代理注册和指定自动配置排除。本节未修改这两项。假设把 AuthService 移到默认扫描范围外、且没有额外扫描或显式注册，单写 `@Service` 不会让它被发现，Controller 的必需依赖可能无法装配并导致启动失败；此处为排错推演，未移动源码或运行实验。

本阶段面试准备以解释 Bean／DI、默认作用域与请求数据、基本生命周期、Boot 入口／扫描／自动配置为核心。请求作用域先掌握 HTTP 请求范围与用途，复杂代理与框架扩展点沿对应专题学习，不要求背源码或完整钩子顺序；这属于学习深度安排，不是对具体公司的出题保证。依据：[SpringBootApplication](https://docs.spring.io/spring-boot/reference/using/using-the-springbootapplication-annotation.html)、[代码组织与扫描范围](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)、[自动配置](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)。

## 10. 自动配置的条件与默认退让

自动配置是 Boot 已提供的配置代码在启动时按条件生效，常用于创建基础设施 Bean。starter 是一组相关依赖的入口；它让相关类可用，不等于所有自动配置都必然生效。应用自己声明的组件、依赖中的类、已经注册的 Bean 分别判断。

以当前应用的 JDBC/MySQL 场景理解：项目引入 MyBatis-Plus 的 Boot 4 starter 与 MySQL 驱动，配置文件提供 `spring.datasource.url`、`username`、`password` 等信息；自动配置启用、相关依赖和其他条件满足时，Boot 可以准备默认 DataSource Bean，供数据访问组件使用。DataSource 是提供数据库连接的对象，常通过连接池实现；连接池依赖可用时 Boot 默认优先 HikariCP。参数分别位于 [公共配置](../../src/main/resources/application.properties) 与 [开发配置](../../src/main/resources/application-dev.properties)／[生产配置](../../src/main/resources/application-prod.properties)，这里不记录实际连接值或凭据。

| 条件标记 | 判断内容 |
| --- | --- |
| `@ConditionalOnClass` | 相关类型是否在应用可用的依赖中；不要求已有该类型的 Bean 实例 |
| `@ConditionalOnMissingBean` | 容器中是否缺少匹配的 Bean；常用于在用户未提供组件时创建默认组件 |

当前默认连接池创建分支检查 DataSource／XADataSource 是否缺失。通常用户显式注册自己的 DataSource Bean 后，默认数据源创建退让，仍可继续应用其他满足条件的自动配置；只修改数据库配置参数，则仍可以让 Boot 创建 DataSource。这里说的是通常的默认候选 Bean，多个数据源与候选资格等边界沿需要时补充，不把退让扩大为所有自动配置关闭。

源码中的数据源接收方与独立命令行的手工数据源分别判断；独立命令中创建普通对象不等于在应用容器中注册 DataSource Bean。以上为依赖、配置与源码分析，未启动应用核对运行时连接池类型。自动准备 Bean 不保证数据库连接成功；MySQL 必须可用且连接参数正确。嵌入式数据库、JNDI 与 R2DBC 属于其他条件路径，不将当前 MySQL 例子的 URL 要求扩大为所有场景。

面试表达可围绕“Boot 已提供配置实现，按依赖与 Bean 等条件启用；用户配置可替代对应默认组件”解释，再指出项目参数位置，不需要背自动配置源码。依据：[自动配置与用户配置替代](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)、[SQL 数据源与连接池](https://docs.spring.io/spring-boot/reference/data/sql.html)、[条件注解](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)、[默认连接池分支](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/jdbc/autoconfigure/DataSourceAutoConfiguration.PooledDataSourceConfiguration.html)。
