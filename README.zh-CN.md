[English](README.md) | 简体中文


# Hot Reload — OpenIDEA 的 Java / MyBatis XML 热加载

改完 Java 或 MyBatis Mapper XML 保存，**自动编译 + 原地热更新**，应用不用重启。
插件把 agent 通过 OpenIDEA 的 `[[contributes.javaAgents]]` 注入 IDE 自带的
Application / Spring Boot / JAR Application 运行配置，Run 和 Debug 都生效，不需要改
VM options，也不需要在项目里加任何依赖。

插件 ID：`weigram.hotreload`（社区市场 publisher `weigram`）。

agent 的内核是 **HotSwapAgent 2.0.5**：类重定义、Spring bean/controller/AOP 重处理都由它负责；
Mapper XML 由本插件的自研 reloader 负责（HA 的 MyBatis/MyBatis-Plus 插件被禁用，避免重复解析和代理冲突）。
HA 通过 `HotswapAgent.premain` 直接启动，**不使用** `-XX:HotswapAgent=external`；JDK 模块 opens
由 agent 运行时调用 `Instrumentation.redefineModule` 完成，**不需要 `--add-opens`**。
方法体和 XML 在 JDK 8–25 上不需要任何 `-XX`；「增删方法/字段、增加接口、类注解」需要 JBR 的
`-XX:+AllowEnhancedClassRedefinition`（JBR 17 实测通过）。**删除接口、替换接口、替换父类仍会报
`attempted to change superclass or interfaces`**，需要重启。

## 它解决什么问题

设置项和交互参考了 [HotSwapHelper](https://gitee.com/gejun123456/HotSwapHelper) 的
issues；下面是这些 issue 在 OpenIDEA 里的对应做法：

| HotSwapHelper issue                                                                 | 原问题                                                              | 本插件                                                                                                                    |
| ----------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| [IBSM5Y](https://gitee.com/gejun123456/HotSwapHelper/issues/IBSM5Y) 自动热更新           | 每次都要手动 Ctrl+F9                                                   | 监听 `files-changed`，保存 `.java` / mapper `.xml` 后自动触发 IDE 的 Build Module（1.2s 防抖）                                        |
| [IAYBKM](https://gitee.com/gejun123456/HotSwapHelper/issues/IAYBKM) XML 没生效         | 外部 agent / 版本 bug，namespace 没刷新                                  | agent 内置 XML 刷新：按 namespace 清掉旧 statement / resultMap / sqlFragment，再用 MyBatis 自己的 `XMLMapperBuilder` 重新解析；解析失败自动回滚旧语句 |
| [IBEIL6](https://gitee.com/gejun123456/HotSwapHelper/issues/IBEIL6) 插件启动异常          | MyBatis 与 MyBatis-Plus 代理类冲突                                     | 不改 MyBatis 的 Mapper 代理，只给 `Configuration.addMappedStatement` 注入一行注册调用；MyBatis-Plus 的 `MybatisConfiguration` 也覆盖到       |
| [IAUQWJ](https://gitee.com/gejun123456/HotSwapHelper/issues/IAUQWJ) 普通 Application  | 只支持特定启动方式                                                        | `javaAgents` 覆盖 Application、Spring Boot、JAR Application 三种配置，Run/Debug 都注入                                             |
| [IANMWH](https://gitee.com/gejun123456/HotSwapHelper/issues/IANMWH) 兼容 PageHelper   | 需要换特殊 agent                                                      | 不碰 MyBatis 内部执行链和代理，PageHelper 的 Interceptor 不受影响                                                                      |
| [IANCYU](https://gitee.com/gejun123456/HotSwapHelper/issues/IANCYU) JDK 21 不支持      | 普通 JDK 不认识 `-XX:HotswapAgent` / `AllowEnhancedClassRedefinition` | 默认不生成任何 `-XX`；方法体和 XML 在 Corretto/MS JDK 21 上直接可用。增强重定义参数只在需要结构热更时手动加                                                  |
| [ICT2OU](https://gitee.com/gejun123456/HotSwapHelper/issues/ICT2OU) Spring Debugger | 生态集成                                                             | 本插件走标准 `java.instrument`，和 IDE 自己的 debug / HotSwap 互不依赖；调试启动同样注入                                                       |

## 与 HotSwapHelper 源码的关系

本插件**没有复制** HotSwapHelper 的代码。最初只读了它的 issues；1.0.1 完成后又读了
v1.1.7（commit `3ead869`）的源码做对照。两者解决同一个问题，但架构不同：

| 方面           | HotSwapHelper 源码                                                             | 本插件                                                                                                                                |
| ------------ | ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| agent 注入     | 自定义 `HotSwapDebugExecutor` + `JavaProgramPatcher`，只对 “Debug with Hotswap” 生效 | OpenIDEA `[[contributes.javaAgents]]`，Application / Spring Boot / JAR Application 的 Run 和 Debug 都自动注入                              |
| 热更实现         | 内置 `hotswap-agent.jar`，依赖 HA 的 Spring/MyBatis 插件                             | 合并 HA 2.0.5 作为类/Spring 内核；XML 由自研 reloader 负责，HA MyBatis/MyBatis-Plus/WatchResources 插件关闭                                          |
| JDK 要求       | JDK 8 需要 DCEVM；11/17 需要 JBR/HA jar，并加 `-XX:HotswapAgent=external` 等          | HA 直接以 `premain` 启动；方法体 / XML 在 JDK 8–25 上不需要 `-XX`；只有增删方法/字段才需要 JBR 的 `AllowEnhancedClassRedefinition`；JDK 模块 opens 由 agent 运行时完成 |
| 自动热更         | 检查 IDEA 的 `RUN_HOTSWAP_AFTER_COMPILE`，没有文件监听（issue IBSM5Y 仍开放）               | 监听 `files-changed`，保存后自动 Build Module                                                                                              |
| 右键 Run/Debug | 只有 Run Dashboard 工具栏的 “Debug with Hotswap” action                            | 编辑器 / 标签页 / 项目树右键只保留 Run、Debug，调用 IDE 自己的上下文运行                                                                                     |
| agent 文件     | 启动时复制到 `~/.hotswap/hotswap-agent.jar`                                        | HA + 自研代码合并成一个 jar，留在插件包内，由 IDE 按哈希校验；构建可复现                                                                                        |
| 设置           | 外部 agent、禁用 HA 插件、不检查 JDK、旧 debugger agent                                   | 自动编译、XML 构建、防抖、通知、maven 兜底                                                                                                         |

`HotSwapHelper-master/` 是你放进来的只读参考源码，已加入 `.gitignore`，不会进入 `.oiplugin`；
本插件没有从它复制代码。

OpenIDEA 没有 `JavaProgramPatcher` / 自定义 `Executor` 扩展点，所以不能照搬
HotSwapHelper 的注入方式；`javaAgents` 是平台提供的等价机制。HotSwapHelper 的
`-XX:HotswapAgent=external` 正是 JDK 21 报 `Unrecognized VM option` 的来源（issue
IANCYU），本插件刻意不生成它，而是直接调用 HA 的 `premain`。

差异点：HA 的 Spring 插件会把 classpath 上所有 `*.xml` 当 Spring bean 定义；mapper XML
会触发 `try to add xml` 错误。本插件在 agent 里把 `SpringPlugin.registerResourceListeners`
替换成空方法，只关闭 **Spring XML 配置** 热更，保留类监听、bean/controller reprocess 和 AOP。
代价是 `applicationContext.xml` 这类 Spring XML 配置改动不再热更（Java Config / Spring Boot 项目通常不用）。

## 组成

```text
plugin-hotreload/
├── plugin.toml                 清单：commands / javaAgents / settings / statusBar
├── settings.schema.json        设置页
├── src/lib.rs                  wasm 组件：保存监听、触发构建、诊断
├── agent/
│   ├── src/…                   自研 agent + HA 集成（Java 8 字节码）
│   ├── lib/asm-9.8.jar         ASM（BSD-3）
│   ├── lib/hotswap-agent-2.0.5.jar  HA 内核（GPL-2.0）
│   └── hotreload-agent.jar     合并后的构建产物，被 javaAgents 注入
├── dist/plugin.wasm            Rust → wasm32-wasip2 构建产物
└── build.sh                    一键构建 + openidea-plugin check
```

agent 做三件事：

1. **启动 HA 内核**：`HotswapAgent.premain("autoHotswap=true,...")`，由 HA 的
   `Hotswapper` 监听 classpath 上的 `.class`，`Spring`/`SpringBoot` 插件 reprocess
   bean、controller、AOP。HA 的 `MyBatis`、`MyBatis-Plus`、`WatchResources` 插件被禁用。
2. **打开 JDK 模块**：Java 9+ 上 `Instrumentation.redefineModule` 打开
   `java.lang`、`java.lang.reflect`、`java.beans` 等包，替代 `--add-opens`。失败时退回
   自研 `ClassRedefiner`（方法体可用，没有 Spring reprocess）。
3. **Mapper XML 刷新**：`Configuration.addMappedStatement` 被注入一行注册调用，
   agent 因此拿到运行中的 MyBatis `Configuration`（MyBatis-Plus 的
   `MybatisConfiguration` 也覆盖）。XML 变化时按 namespace 清旧条目、重新解析；
   失败回滚，不会把应用留在半坏状态。

### hotswap-agent-2.0.5.jar 的功能和逻辑

HA 不是一个额外的进程，也不是让用户再加一个 `-javaagent`。`agent/package.py` 把
`hotswap-agent-2.0.5.jar` 解包后，和 ASM、插件自己的类合并进
`agent/hotreload-agent.jar`，并替换 HA 的 MANIFEST 和默认
`hotswap-agent.properties`。

- **启动**：插件调用 `HotswapAgent.premain(args, instrumentation)`。HA 创建
  `PluginManager` / `PluginRegistry`，读取 `hotswap-agent.properties`，初始化启用的插件。
- **插件发现**：`PluginRegistry` 扫描 HA 的 `Spring`、`SpringBoot` 等插件；
  `disablePlugin` 指定的插件被跳过。本插件禁用 `MyBatis`、`MyBatisPlus`、
  `WatchResources`。
- **监听**：`autoHotswap=true` 时，`TreeWatcherNIO` 监听 class 输出目录；
  `HotswapperPlugin` 收到变化的 `.class` 后交给 `PluginManager`。
- **重定义**：`PluginManager.hotswap` 调用 `Instrumentation.redefineClasses`。
  成功打印 `RELOAD`，失败打印 JVM 异常。HA 不能突破 JVM 限制：标准 HotSpot 只允许
  方法体变化；结构变化仍需要 JBR 和 `-XX:+AllowEnhancedClassRedefinition`。
- **框架 reprocess**：HA 的 Spring / Spring Boot 插件监听类和上下文变化，在类重定义后
  reprocess bean 定义、controller 和 AOP 元数据。
- **配置参数**：`autoHotswap=true`、`disablePlugin=MyBatis`、
  `disablePlugin=MyBatisPlus`、`disablePlugin=WatchResources`、`LOGGER=info`；
  不使用 `-XX:HotswapAgent=external`。
- **许可**：HA 2.0.5 为 GPL-2.0，在合并 jar 中保持未修改；插件自身也是 GPL-2.0-only。

```text
保存文件
   |
   v
OpenIDEA build.buildModule
   |
   v
target/classes 变化
   |
   v
HA TreeWatcherNIO -> HotswapperPlugin -> PluginManager
   |
   v
Instrumentation.redefineClasses
   |
   v
HA Spring 插件 reprocess bean/controller/AOP
```

wasm 组件只负责「什么时候编译」和「怎么诊断」：它不改项目的构建文件，不启动第二个
JVM，也不往项目里写文件。

## 构建

需要：Rust + `wasm32-wasip2` target、`openidea-plugin` CLI、JDK 9+（编译 agent）、
Python 3。

```bash
./build.sh
```

构建是**可复现**的：agent jar 的条目按名称排序、时间戳固定，所以同样的源码得到同样的
字节。OpenIDEA 会在批准 `java.agent` 时记录 jar 的哈希；可复现构建意味着改插件代码后
重新构建、重新批准一次即可，日常重启 IDE 不会反复要求授权。

## 安装与使用

```bash
# 开发期：软链，改完在 Settings › Plugins 点 Reload
openidea-plugin link

# 本地安装（未签名，开发用）
openidea-plugin pack

# 发布到社区市场：用 publisher 私钥签名
openidea-plugin pack . --key /secure/weigram.key --publisher weigram
```

1. `Settings › Plugins › Hot Reload` 里批准：
   - `java.agent`（高，必需，agent 会跑在你的 Java 应用里）
   - `ide.commands`（高，自动触发 IDE 构建）
   - `clipboard`（可选，复制增强 VM 参数）
   - `process:mvn`（可选，`buildBackend = maven` 时手动编译用）
2. 用 IDE 自带的 **Run / Debug** 启动 Application、Spring Boot 或 JAR Application 配置。
   插件不会新建运行配置；它只让 IDE 在原来的命令行里多一个 `-javaagent:`。
3. 改 Java 保存 → 自动 Build Module → agent 热更；改 Mapper XML 保存 → 构建把 XML
   复制到 `target/classes` → agent 刷新 namespace。

应用控制台里应看到：

```text
15:00:05.744 [hotreload] INFO  Hot Reload agent 1.1.0 (pid 65453, OpenJDK 64-Bit Server VM 17.0.14+1-b1367.22)
15:00:05.900 [hotreload] INFO  class/Spring engine: HotSwapAgent 2.0.5 (autoHotswap, Spring plugin on, MyBatis plugins off)
15:00:05.950 [hotreload] INFO  watching 1 class directories: …/target/classes
15:00:08.137 HOTSWAP AGENT: … RELOAD … Reloading classes [com.qctc.lab.service.DemoService] (autoHotswap)
15:00:08.180 [hotreload] INFO  reloaded mapper XML DemoMapper.xml -> com.qctc.lab.mapper.DemoMapper (1 statements)
```

### 命令

| 命令                                     | 快捷键              | 作用                                                                          |
| -------------------------------------- | ---------------- | --------------------------------------------------------------------------- |
| `Hot Reload: Run Current File`         | —                | 右键 / 命令面板：用 IDE 自己的上下文运行配置启动当前类（临时 Application / Spring Boot 配置），agent 自动注入 |
| `Hot Reload: Debug Current File`       | —                | 同上，Debug 启动                                                                 |
| `Hot Reload: Compile & Reload`         | ⌘⌥R / Ctrl+Alt+R | 立即编译当前文件所在模块；不依赖自动编译                                                        |
| `Hot Reload: Diagnose`                 | —                | 打开 Markdown 诊断页：授权、项目 JDK、输出目录、运行配置里的增强参数、agent 日志示例                        |
| `Hot Reload: Copy Enhanced VM Options` | —                | 复制 `-XX:+AllowEnhancedClassRedefinition`，用于 JBR 结构热更                        |

编辑器右键、编辑器标签页右键、项目树右键只保留 **Run / Debug** 两个入口（图标 #11e3e9）。
Compile 走状态栏点击或 `⌘⌥R`；Diagnose / Copy VM 在 Settings 的 Hot Reload 页或命令面板。
Run / Debug 不自己起 `mvn`，而是调用 IDE 的 `run.runContext` / `run.debugContext`：
上下文配置仍然是 IDE 的 Application / Spring Boot / JAR Application，所以 `javaAgents`
注入照常生效。项目树里右键文件时会先打开该文件，再执行上下文运行。

状态栏右侧的 **Hot Reload** 可点击，等同于 Compile & Reload。

> 从旧版 HotswapAgent 方案迁移时，**删掉运行配置里旧的
> `-javaagent:…/hotswap-agent-2.0.5.jar` 和 `-XX:HotswapAgent=external`**。
> 本插件的 agent 已经内置 HA 2.0.5；再挂一个外部 HA 会重复初始化/重定义。
> 不需要 `--add-opens`，agent 会在运行时打开 JDK 模块。

### 设置

| 设置          | 默认                       | 说明                                                                          |
| ----------- | ------------------------ | --------------------------------------------------------------------------- |
| 保存后自动编译     | 开                        | 关闭后仍可用命令/快捷键手动编译                                                            |
| XML 保存也触发构建 | 开                        | Mapper XML 需要先复制到 `target/classes`                                          |
| 编译方式        | `ide`                    | `ide` 调 `build.buildModule`；`maven` 只影响手动命令（自动编译始终走 IDE，避免 5 秒调用预算被 mvn 占满） |
| Maven 编译参数  | `-q -DskipTests compile` | 手动 maven 模式使用                                                               |
| 自动编译防抖（毫秒）  | 1200                     | 连续保存合并成一次构建                                                                 |
| 自动编译后回到编辑器  | 开                        | 触发 IDE 构建后把焦点从 Build 窗口还给编辑器；Build 窗口仍会打开                                   |
| 显示通知        | 开                        | 只在错误/缺授权时提示；正常热更日志在 Run 控制台                                                 |

## Capabilities and security

The marketplace lists every capability the package asks for. This plugin needs:

- **`java.agent` (high risk, required).** The agent runs arbitrary code inside the Java application JVM. It is the only way to redefine loaded classes and refresh MyBatis mapper statements without restarting. The agent is bundled from this repository, its path is validated inside the plugin package, and OpenIDEA records the jar hash when the capability is granted. Denying or revoking the grant means the application starts without the agent.
- **`ide.commands` (high risk, required).** Used to invoke `build.buildModule` after a save and, for automatic builds, `toolwindow.focusEditor`. The plugin does not build shell command strings.
- **`process:mvn` (optional).** Used only for the manual Maven fallback (`buildBackend = maven`). Automatic builds use the IDE build. The plugin does not start arbitrary programs.
- **`clipboard` (optional).** Used only by **Hot Reload: Copy Enhanced VM Options**.
- **`events` and `project.read` (low risk).** Used to observe saved files and read the project's build/Java configuration.

There is no network capability and no telemetry. The bundled HotSwapAgent 2.0.5 is GPL-2.0 and ASM 9.8 is BSD-3-Clause; see `agent/lib/README.md`.

## 能力边界（实测）

测试工程：Spring Boot 3.4.3 + MyBatis-Plus 3.5.10.1 + H2，`DemoService` + `DemoMapper.xml`。
每次只改文件、重新编译，进程 PID 始终不变。

| 变更               | JDK 8 Corretto  | JDK 17 JBR                                         | JDK 21 Corretto  | JDK 25 JBR        |
| ---------------- | --------------- | -------------------------------------------------- | ---------------- | ----------------- |
| 方法体              | ✅ `v3 → v4`（HA） | ✅ `v6 → v9`（HA）                                    | ✅ `v9 → v10`（HA） | ✅ `v10 → v11`（HA） |
| 新增 Controller 映射 | —               | ✅ `/extra2` 200，带 `AllowEnhancedClassRedefinition` | ❌ 标准 JVM 不允许加方法  | ✅ 带参数（HA 同一路径）    |
| 加方法 / 字段         | ❌ 标准 JVM        | ✅ 带 `AllowEnhancedClassRedefinition`               | ❌ 标准 JVM         | ✅ 带参数             |
| Mapper XML       | —               | ✅ `XML2 → XML5`                                    | ✅ `XML3 → XML4`  | ✅ 自研 reloader     |
| 不加重定义参数时启动       | ✅               | ✅                                                  | ✅                | ✅                 |
| HA Spring 插件初始化  | —（无 Spring）     | ✅ Spring 6.2.3                                     | ✅ Spring 6.2.3   | ✅ Spring 6.2.3    |

结论：

- **方法体在 JDK 8–25 上开箱可用**，不需要 `-XX`；XML 在 JDK 17 / 21 / 25 实测
  （plain MyBatis 与 MyBatis-Plus 各一套），同一份 agent 不做版本分支。
- **Spring bean / controller / AOP 由 HA 2.0.5 的 Spring 插件 reprocess**：新增
  `@GetMapping`、改 controller 方法、AOP 注解等走 HA 的 class listener；实测新增
  `/extra2` 后直接返回 200，不需要重启。
- **增删方法/字段**需要 JetBrains Runtime，并在运行配置 VM options 里加
  `-XX:+AllowEnhancedClassRedefinition`。右键 **Run Current File** 用的是 IDE 的临时
  Application 配置；要让临时配置也继承这个参数，把它加到
  `Edit Configurations → Edit configuration templates → Application → VM options`。
  这是 JVM 能力，不是插件能绕过的；加方法失败时
  HA 会在控制台打印 `attempted to add a method`，本插件的 doctor 也会提示。方法体热更不需要
  这个参数。
- **删除接口 / 替换接口 / 替换父类仍不支持**：JBR 17 实测可以**增加**接口，但删除
  `implements Marker`、把 `implements A` 换成 `implements B`，或把 `extends BaseA`
  换成 `extends BaseB` 都会报 `class redefinition failed: attempted to change superclass
  or interfaces`，需要重启。这是 JVM 的结构限制；HotSwapHelper 的 README 也把层次替换
  列为不支持操作。
- Mapper XML 解析失败时，agent 会恢复旧 statement 并打印错误，应用继续可用。
- **Spring XML 配置（`applicationContext.xml`）不热更**：为了保证 mapper XML 不被 HA 当成
  Spring bean 定义解析，agent 关闭了 HA 的 `registerResourceListeners`。Java Config /
  Spring Boot 项目不受影响；如果用 XML 声明 bean，改完需要重启。
- **MyBatis / MyBatis-Plus 的 HA 插件关闭**：mapper XML 由自研 reloader 处理；mapper
  接口本身的增删方法走 HA 类重定义，但 MyBatis 的 mapper 注册是否刷新未单独回归，建议
  XML 与接口一起改时重启确认。
- 改**方法签名**、record 组件：正在执行的调用点仍指向旧结构，等同于需要重启。
- `@Value` / `@ConfigurationProperties` 的重新绑定由 HA 的 Spring 插件尝试处理，未单独回归；
  新增 Bean 定义通常仍建议重启。
- IDE 的 `javaAgents` 不覆盖 Maven/Gradle goal、JUnit、Tomcat、远程 JVM；这些启动方式
  如果确实需要，得自己加 `-javaagent`。

### Build 窗口报 `cannot start mvn`？

自动编译调用 IDE 的 `build.buildModule`，最终由 OpenIDEA 的 Maven 集成执行。如果 PATH 里没有
`mvn`，Build 窗口会报 `cannot start mvn: No such file or directory`。两种修法：

- 在 `Settings › Build, Execution, Deployment › Maven` 把 **Maven home path** 指到本机 Maven；
- 或把 Maven 的 `bin` 加进 PATH（本次验证机器上放的是 `/opt/homebrew/bin/mvn` 包装脚本）。

插件不会自己下载或安装 Maven。

### 没有输出目录？

agent 启动时会打印 `watching N class directories`。如果没有这一行或 `N = 0`：

- 用 **IDE 的 Application / Spring Boot / JAR Application 配置**，不要用 Maven goal；
- 不要用 `spring-boot:repackage` 后的 fat jar（类在 `BOOT-INF/classes`，不是 exploded）；
- `mvn spring-boot:run` 是 exploded classpath，可以使用；
- 多模块工程从根目录启动也可以：`java.class.path` 会包含各模块的 `target/classes`。

## 测试

仓库自带三个不依赖 IDE 的端到端测试：

```bash
# 方法体 + Mapper XML；JDK 8 / 11 / 17 / 21 / 25 都可跑
./test/e2e.sh

# 结构变更矩阵；必须用 JBR，并自动带 -XX:+AllowEnhancedClassRedefinition
JAVA=/path/to/jbr/bin/java ./test/structure.sh

# 对每个已安装 JDK 分别跑上面两个脚本
./test/jdk-matrix.sh
```

`structure.sh` 分成三个独立 JVM 组，失败的重定义不会污染下一组；完整矩阵由 `test/jdk-matrix.sh` 生成。

`test/jdk-matrix.sh` 默认用 `/usr/libexec/java_home -V` 发现的 JDK；也可以指定：

```bash
# 也可以把下载的 JBR 目录交给脚本
JBR_MATRIX_DIR=/path/to/jbr ./test/jdk-matrix.sh

# 或显式指定所有 JDK
JDK_MATRIX_HOMES="$JAVA8:$JAVA11:$JBR17:$JBR21:$JBR25:$JAVA21:$JAVA25" \
  ./test/jdk-matrix.sh
```

`test/jdk-matrix.sh` 每次运行都会重新生成下表：

<!-- BEGIN JDK-MATRIX -->
<!-- generated by test/jdk-matrix.sh; do not edit by hand -->
| 修改 | JDK 8 | JDK 11 | JBR 17 | JBR 21 | JDK 21 | JBR 25 | JDK 25 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 方法体 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mapper XML | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 增加方法/字段 | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅ | ✅ | ❌ 未测试（需 JBR） | ✅ | ❌ 未测试（需 JBR） |
| 增加类注解 | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅ | ✅ | ❌ 未测试（需 JBR） | ✅ | ❌ 未测试（需 JBR） |
| 增加接口 | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅ | ✅ | ❌ 未测试（需 JBR） | ✅ | ❌ 未测试（需 JBR） |
| 删除方法/字段 | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅ | ✅ | ❌ 未测试（需 JBR） | ✅ | ❌ 未测试（需 JBR） |
| 删除接口 | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ✅ | ❌ 未测试（需 JBR） | ✅ | ❌ 未测试（需 JBR） |
| 替换接口 | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ✅ | ❌ 未测试（需 JBR） | ✅ | ❌ 未测试（需 JBR） |
| 替换父类 | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ❌ 层次变更不支持 | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ❌ 未测试（需 JBR） |
<!-- END JDK-MATRIX -->

图例：✅ 通过；❌ 失败或未测试。**方法体 / Mapper XML** 行不添加 `-XX:+AllowEnhancedClassRedefinition`；**结构变更**行只在支持该参数的 JBR 上执行并自动添加该参数。JDK 8 / 11 / 21 / 25 的结构变更单元格是“未执行”，不是“带参数测试失败”。

JBR 版本差异：JBR 17 上删除/替换接口仍失败；JBR 21 / 25 上删除/替换接口通过，但**替换父类**在三个 JBR 上都失败。测试用的 JBR 21 / 25 是自行下载的：
`/Users/a1/Desktop/qy/soft/jbr/jbr-21.0.9-osx-aarch64-b1038.91` 和
`/Users/a1/Desktop/qy/soft/jbr/jbr-25.0.5.0.3-osx-aarch64-b700.17`。

JDK 8 使用 fixture 的 `jdk8` Maven profile，自动切到 H2 1.4.200；其他 JDK 使用 H2 2.3。

### 直接操作 OpenIDEA 的实测

在 OpenIDEA 0.1.48 里打开 `test/fixture-structure`，使用 linked plugin
`weigram.hotreload`，运行配置：

```text
JBR 17
-XX:+AllowEnhancedClassRedefinition
demo.App
```

Run 控制台中的实际命令行包含：

```text
-javaagent:/.../agent/hotreload-agent.jar
```

说明 `javaAgents` 注入生效。然后在 IDE 打开项目、修改 `Target.java` 并保存：

- 方法体：状态文件从 `value=A` 变为 `value=V2`，通过；
- 增加方法/字段/类注解：`extra=YES field=YES deprecated=true`，通过；
- 增加接口：`marker=YES`，通过；
- 删除/替换接口、替换父类：控制台报
  `attempted to change superclass or interfaces`；替换失败后继续改其他结构不会恢复，
  需要重启应用。

IDE 测试使用 `test/fixture-structure/.openidea/run.toml` 本地运行配置；该文件不提交。

## 开发

```bash
cargo test                       # wasm 逻辑（纯函数 + mock host）
./build.sh                       # agent + wasm + check

# 端到端：plain MyBatis + XML 刷新 + 类重定义，断言进程只启动一次
MVN=/path/to/mvn ./test/e2e.sh
```

`test/e2e.sh` 默认用 `java`/`javac`/`mvn`；JDK 不是 11+ 时会跳过（fixture 的 H2 需要
Java 11，JDK 8 的类重定义已单独验证）。

agent 源码在 `agent/src/com/hotreload/agent/`：

| 文件                         | 作用                                                                                 |
| -------------------------- | ---------------------------------------------------------------------------------- |
| `HotReloadPremain`         | `premain` / `agentmain` 入口                                                         |
| `Agent`                    | 打开 JDK 模块、启动 HA 2.0.5、注册 transformer、启动 XML watcher                                |
| `JdkModuleOpens`           | `Instrumentation.redefineModule` 打开 `java.lang` / `java.beans` 等包，替代 `--add-opens` |
| `HotswapSpringPatch`       | 关掉 HA 的 Spring XML 资源监听，避免 mapper XML 被当成 Spring bean 定义                           |
| `Watcher`                  | 轮询输出目录；HA 模式下只处理 XML，回退模式处理 class + XML                                            |
| `ClassRedefiner`           | HA 不可用时的回退 `redefineClasses`，结构变更失败时给出 JBR 修复步骤                                    |
| `MyBatisConfigTransformer` | 给 `addMappedStatement` 注入注册调用（ASM）                                                 |
| `MyBatisConfigRegistry`    | 活着的 `Configuration` 弱引用列表                                                          |
| `MyBatisXmlReloader`       | 按 namespace 清旧条目、`XMLMapperBuilder` 重解析、失败回滚                                       |

## 许可

插件与 agent：GPL-2.0-only（见 `LICENSE`）。
内置 ASM 9.8：BSD-3-Clause（见 `agent/lib/README.md`）。
内置 HotSwapAgent 2.0.5：GPL-2.0（见 `agent/lib/README.md`）。
