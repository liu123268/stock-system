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

## 11. Profile 与配置加载、覆盖

Profile 用环境名称选择一组配置，让同一套应用代码在开发、测试、生产环境使用不同参数。项目的 [application.properties](../../src/main/resources/application.properties) 提供公共配置，另外三份 `application-dev.properties`、`application-test.properties`、`application-prod.properties` 分别提供对应环境的配置。

公共文件当前声明 `spring.profiles.default=dev`：没有显式激活其他 Profile 时默认选择 dev。按单个环境理解，未显式指定时组合公共文件与 dev 文件，选择 test 时组合公共文件与 test 文件，选择 prod 时组合公共文件与 prod 文件；dev 不因为是默认值就额外加入显式选择的 test。Profile 文件补充配置，同一位置的同名配置项可覆盖公共文件中的值，未重写的项继续使用公共值。

应用可用 `--spring.profiles.active=test` 这样的启动参数选择环境；它是交给应用的参数，不是独立命令。[start-dev.cmd](../../scripts/start-dev.cmd) 与 [start-test.cmd](../../scripts/start-test.cmd) 当前分别通过 Maven 插件选项 `spring-boot.run.profiles=dev`／`test` 选择环境，不能把 Maven 插件属性名与应用自己的参数名混为一项配置。

启动脚本与配置文件不靠放在同一目录建立联系。测试脚本在项目根目录运行 Maven，使用 `-Dspring-boot.run.arguments=--server.port=18082` 要求插件把端口选项传给应用。应用的 `main(String[] args)` 接收启动参数，再通过 `SpringApplication.run(StockSystemApplication.class, args)` 交给 Boot；Boot 将参数作为配置来源处理。方向是脚本启动程序并传参，配置文件不反过来读取脚本。

`application-test.properties` 是配置数据文件，不是 Java 测试类。Maven 按资源目录约定把 `src/main/resources` 中的内容提供到运行类路径，Boot 按默认位置和 `application-{profile}.properties` 命名规则加载相应文件。这里的类路径是运行时查找类和资源的位置，不要求源文件与启动脚本物理相邻。依据：[Maven 启动插件的 arguments／profiles 参数](https://docs.spring.io/spring-boot/maven-plugin/run.html)、[Maven 资源目录约定](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html)。

真实端口例子：公共配置是 `server.port=8082`，生产文件当前也显式配置为 8082；测试文件本身没有另设端口。测试启动脚本传入 `--server.port=18082`，按普通应用启动参数优先于文件配置的规则，脚本要求后端使用 18082。此处根据源码和优先级分析，未运行脚本或测试。

本轮比较的常规来源可按“命令行参数 > 操作系统环境变量 > 配置文件”理解；不是完整来源清单。配置文件中还要区分外部文件、打包内文件、环境专用文件及显式导入等顺序，不能一概说任意 Profile 文件都高于所有公共文件。当前 dev／prod 文件另有 `spring.config.import` 声明，可能加载额外文件，也不等于 Spring 会自动读取任意 `.env` 文件。

参数加载与覆盖得到最终配置后，自动配置才据此及其他条件准备基础组件；选择环境不是另写一套 Service，也不等于改动业务源码。本阶段掌握环境选择、公共与环境文件组合、同名项覆盖及常见启动覆盖即可；Profile 组、多环境同时激活和完整优先级细节按实际需求展开，不新增复杂配置。依据：[Profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html)、[外部化配置与覆盖顺序](https://docs.spring.io/spring-boot/reference/features/external-config.html)。

## 12. 占位符与 Value：配置值进入构造器

`${名称:默认值}` 由 Spring 解析：从配置来源查找指定名称，找不到时采用冒号后的默认值。名称可以由环境变量或其他配置来源提供，不是让 Java 类打开某个同名文件。环境变量是运行环境向进程提供的命名值，启动配置也可以引用它。

项目 [开发配置](../../src/main/resources/application-dev.properties) 与生产配置声明 `stock-system.market.mode=${MARKET_MODE:longbridge}`。这里先查 `MARKET_MODE`，找不到则使 `stock-system.market.mode` 取值为 `longbridge`；测试配置直接声明该项为 `mock`。模式名在本单元只作配置字符串，不扩展供应商调用。

[StockMarketPreviewService 构造器](../../src/main/java/com/stock/system/stock/service/StockMarketPreviewService.java) 的参数是 `@Value("${stock-system.market.mode:mock}") String marketMode`，方法体执行 `this.marketMode = marketMode`。Spring 创建这个 Service 时解析 Value、把字符串作为构造器参数传入，再由构造器保存到字段；真实类还有其他对象依赖，不能把教学精简示意当作替换源码。

两处默认值的查找目标不同：配置文件表达式缺少 MARKET_MODE 时使用 longbridge；Value 表达式只有找不到 `stock-system.market.mode` 配置项时才回退到 mock。不能因注解里出现 mock，就断言开发环境最终模式为 mock。假设选 dev、没有 MARKET_MODE 且没有其他覆盖，链路为“缺少 MARKET_MODE → 配置项解析为 longbridge → Value 取得 longbridge → 构造器字段保存 longbridge”；若该名称由环境变量明确提供 directory，且无更高优先级覆盖，则传入 directory。以上为源码规则推演，未读取或修改当前进程环境、未启动应用。

Value 注解来自 Spring，可用于字段或构造器等注入位置，本例在构造器参数上。普通的这类注入发生在 Bean 创建阶段；后续业务方法使用已保存的字段，不会每次请求自动重新读取文件，修改配置也不意味着该字段自动刷新。依据：[Value 注入与默认值](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/value-annotations.html)、[配置占位符](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.property-placeholders)。

## 13. ConfigurationProperties：同组配置绑定到对象

先连接配置来源、查询入口与接收对象：application-dev.properties、环境变量、启动参数等提供配置，Spring 的 Environment 为这些来源提供统一查询入口；项目 [MarketRedisProperties](../../src/main/java/com/stock/system/stock/market/MarketRedisProperties.java) 是接收其中一组有效值的 Java 类。`@ConfigurationProperties` 的绑定从 Environment 可访问的配置中，按 `prefix = "stock-system.market.redis"` 匹配并设置对象属性，不是另建一份配置去替换原文件，也不会把字段值反向写入环境配置。修改某个环境的参数，直接修改已有环境配置项或相应外部来源即可，不需要另建覆盖用的 Java 类；第 10 节自定义 Bean 替代默认 Bean 是另一种机制。

使用 Environment 后并非必须再建参数类。消费方可以显式调用 `environment.getProperty("stock-system.market.redis.quote-ttl")` 查询；也可以让 Spring 先把同组参数转换并保存进配置对象，业务通过 `properties.getQuoteTtl()` 读取已保存的 Duration。后者把同组字段、类型和默认值集中起来，业务不用反复写配置键名或处理文本转换。前述 Value 例子也在创建 Bean 时先查询并注入、再保存为字段，并非每次业务调用都查询 Environment。大写的 MARKET_REDIS_QUOTE_TTL 是配置名称，不因大写就成为 Java 常量；`${…}`、Value、ConfigurationProperties 绑定或显式 getProperty 调用才建立相应的查值过程，普通 Java 标识符不会自动查配置。

| 配置项（省略共同前缀） | Java 属性 | 类型与值示例 |
| --- | --- | --- |
| `enabled` | `enabled` | true → boolean |
| `quote-ttl` | `quoteTtl` | 5s → 表示 5 秒的 Duration |
| `lock-wait` | `lockWait` | 800ms → 表示 800 毫秒的 Duration |

横线形式名称可以匹配 Java 驼峰名称，是 Boot 的宽松绑定规则。Duration 是 Java 表示时间长度的类型；绑定时按目标类型转换配置文本。当前可变配置类提供 getter／setter，绑定通过 setter 设置属性，消费方通过 getter 读取。缺少某个配置项时，可保留对象字段自己的初始化值；这不表示该初始化值被写回全局配置来源。

当前 quote-ttl 在环境文件的占位符中默认 5s，Java 字段也初始化为 5 秒，分别处理“找不到 MARKET_REDIS_QUOTE_TTL 名称”和“该属性未从外部绑定赋值”的情况。两处默认不是 Spring 强制要求重复声明；项目 [缓存网关测试的构造代码](../../src/test/java/com/stock/system/stock/market/CachingLongbridgeMarketGatewayTest.java) 还直接 new MarketRedisProperties，不经过配置绑定，也能使用字段默认值。参数类集中定义字段类型、保存参数；外部配置让不同环境可调整取值而不改业务 Java 实现。给某个环境设置 30s 时，配置会把对象字段设为 30 秒，不要求同时把 Java 的兜底值从 5 改成 30；这是赋值替换字段初始值，不是替换其他配置文件。

该类型当前没有 Component 注解；项目在缓存组件上使用 `@EnableConfigurationProperties(MarketRedisProperties.class)` 注册并启用绑定。[CachingLongbridgeMarketGateway](../../src/main/java/com/stock/system/stock/market/CachingLongbridgeMarketGateway.java) 与 RedisMarketSharedCache 各自有启用条件：相关组件实际被加载时，相应注册声明才参与处理，不能推断所有 Profile 都必然创建该配置 Bean。前者构造器接收配置对象、保存为 `properties` 字段，业务中调用 `properties.getQuoteTtl()` 取得缓存时间。

假设最终有效配置中的 quote-ttl 为 30s，则绑定后 getter 返回表示 30 秒的 Duration，而不是字段初始的 5 秒。配置来源 → Environment 统一查询入口 → 前缀与属性匹配、类型转换并保存到配置 Bean → 构造器注入消费组件 → getter 读取已保存的值，是本单元完整链路。此处是规则与源码推演，未修改配置、未运行应用；Value 适用于指定单项值，同组配置可集中使用 ConfigurationProperties，不要求用两套方式重复读取同一组参数。

依据：[ConfigurationProperties 的前缀定义](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/context/properties/ConfigurationProperties.html)、[EnableConfigurationProperties 注册支持](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/context/properties/EnableConfigurationProperties.html)、[宽松绑定与 Duration 转换](https://docs.spring.io/spring-boot/reference/features/external-config.html)。

## 14. 登录请求：MVC、参数绑定、校验与响应

Spring MVC 是 Spring 已提供的一套 Java 组件，项目通过依赖引入后，与业务代码一起运行。浏览器发送的是接口地址、请求方式和数据；MVC 根据接口映射找到 Java 处理方法，准备参数、组织调用，再将结果写进 HTTP 响应。它不是某一个业务类或独立部署的服务。Service 层是开发者按业务职责组织的一组 Java 类，AuthService 是其中的一个类；“层”表示职责分工，登录规则仍由开发者编写，Service 注解本身不会生成这些业务规则。

以直接访问后端的 POST /api/auth/login 为例，默认端口沿 application.properties 的 8082；前端开发代理和生产反向代理是外层转发环节。内嵌 Tomcat 接收 HTTP 请求、管理 Servlet 的运行；Servlet 是处理 Web 请求的 Java 标准组件，DispatcherServlet 是 Spring MVC 的统一入口。通常执行路径为 Tomcat → Servlet Filter／Security 过滤器链 → DispatcherServlet → 定位 Controller 方法 → 参数读取与校验 → Controller → Service → Mapper／数据库；成功返回时，经消息转换器用 Jackson 把响应对象转换成 JSON。HandlerMapping 负责定位处理方法，HandlerAdapter 负责适配调用及参数／返回值处理。

[AuthController](../../src/main/java/com/stock/system/auth/controller/AuthController.java) 的类级 RequestMapping /api/auth 与方法级 PostMapping /login 共同匹配 POST /api/auth/login。`@RequestBody` 通过消息转换器将 JSON 转成 LoginRequestDTO；`@Valid` 触发该对象的校验，本接口未声明 BindingResult，校验失败在进入 Controller 方法体前抛出 MethodArgumentNotValidException。DTO 的 NotBlank 拒绝 null、空串和纯空白，Size 对登录密码要求 4～64 字符；这项长度兼容旧凭据，不应误用为注册／改密的新密码规则。

Controller 接收和返回 HTTP 数据，AuthService 负责查账号、密码匹配、账号状态和签发 Token 等业务；结构校验通过不代表账号存在、密码正确或权限满足。`Result.success(authService.login(request))` 先执行 Service 得到 LoginResponseVO，再包进 Result；RestController 包含响应体语义，Spring 将返回对象写入 HTTP 响应，业务代码不必手动拼 JSON。

[GlobalExceptionHandler](../../src/main/java/com/stock/system/common/exception/GlobalExceptionHandler.java) 的 RestControllerAdvice／ExceptionHandler 把 MVC 可解析的异常转成统一响应：该请求的参数校验错误返回 HTTP 400 与 INVALID_REQUEST，BusinessException 按 ErrorCode 返回相应 HTTP 状态及错误信息。此处的“全局”是 MVC 异常处理范围；过滤器中在进入 MVC 前直接拒绝的请求，由安全链自身处理，不能推断 ControllerAdvice 自动捕获所有层的异常。

Filter 与 Interceptor 参与处理的是同一次 HTTP 请求，不能理解成“Filter 检查用户权限，Interceptor 检查方法是否合格”。Java 方法原本就在应用中；用户传入请求，由 MVC 选择对应的处理目标，并非用户传进来一个方法供拦截器过滤。对这里的 Controller 请求，前置 Filter 可以在 MVC 尚未选择方法时处理请求；MVC 找到目标后调用 HandlerInterceptor，并直接提供目标处理器信息，拦截器还可参与后置和完成回调。读取目标方法上的标记，是利用方法信息决定如何处理请求，不是判断 Java 方法写得是否合格。

对照场景：登录请求进入后，假设由 Filter 先生成统一请求编号，让后续日志使用；MVC 再定位登录 Controller 方法；假设已配置 Interceptor，它可检查该方法是否带有“记录业务操作”的标记，按约定参与记录，再继续执行 Controller。请求编号和标记处理均为教学设定，并非项目已实现功能。项目 JWT 认证实际沿 [JwtAuthenticationFilter](../../src/main/java/com/stock/system/security/JwtAuthenticationFilter.java) 和 SecurityConfig；无 Token 的登录请求由安全规则放行，不会免除 AuthService 对账号密码的验证。当前未发现自定义 HandlerInterceptor，其职责区别属于知识准备。

用途重叠不等于整体可互换：对覆盖相同范围的 Controller 请求，统计耗时可以选 Filter 或 Interceptor；但若要求在 MVC 开始查找方法之前生成编号，不能直接改用较晚才执行的 Interceptor。若规则依赖目标方法上的标记，Interceptor 能直接取得目标信息，Filter 的调用参数通常只有请求、响应和后续链路，需额外配合才能完成同样判断。两者都能记录日志、决定是否继续，区别在接入位置、可直接获取的信息与覆盖范围，不能只按“权限／日志”等用途区分。对当前同步处理路径，前置顺序可记为 Filter → DispatcherServlet → Interceptor（若配置）→ Controller；请求可在中间被拒绝，并非每次都执行 Service／SQL。

依据：[DispatcherServlet 请求处理](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/sequence.html)、[RequestBody 与参数校验](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html)、[MVC 拦截器](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/handlermapping-interceptor.html)。本节按当前源码与框架契约讲解，未启动服务或执行登录请求。
