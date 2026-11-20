[English](README.md) | 简体中文


# Hot Reload — OpenIDEA 的 Java / MyBatis XML 热加载

改完 Java 或 MyBatis Mapper XML 保存，**自动编译 + 原地热更新**，应用不用重启。
插件把 agent 通过 OpenIDEA 的 `[[contributes.javaAgents]]` 注入 IDE 自带的
Application / Spring Boot / JAR Application 运行配置，Run 和 Debug 都生效，不需要改
VM options，也不需要在项目里加任何依赖。实现思路参考了
[HotSwapHelper](https://github.com/gejun123456/HotSwapHelper) 项目。

插件 ID：`weigram.hotreload`（社区市场 publisher `weigram`）。

agent 的内核是 **HotSwapAgent 2.0.5**：类重定义、Spring bean/controller/AOP 重处理都由它负责；
Mapper XML 由本插件的自研 reloader 负责（HA 的 MyBatis/MyBatis-Plus 插件被禁用，避免重复解析和代理冲突）。
HA 通过 `HotswapAgent.premain` 直接启动，**不使用** `-XX:HotswapAgent=external`；JDK 模块 opens
由 agent 运行时调用 `Instrumentation.redefineModule` 完成，**不需要 `--add-opens`**。
方法体和 XML 在 JDK 8–25 上不需要任何 `-XX`；「增删方法/字段、增加接口、类注解」需要 JBR 的
`-XX:+AllowEnhancedClassRedefinition`（JBR 17 实测通过）。**删除接口、替换接口、替换父类仍会报
`attempted to change superclass or interfaces`**，需要重启。

## 特性

- **保存即热更**：保存 Java 文件或 MyBatis Mapper XML 后自动触发 IDE 构建，agent 在运行中的 JVM 里重定义类或刷新 Mapper 语句；Run 和 Debug 都生效，应用不重启。
- **JDK 8–25**：方法体和 Mapper XML 在 JDK 8 / 11 / 17 / 21 / 25 上可用，不需要额外 VM 参数。
- **Spring 支持**：HotSwapAgent 2.0.5 负责类重定义和 Spring bean / controller / AOP 的 reprocess。
- **MyBatis / MyBatis-Plus**：自研 XML reloader 按 namespace 清旧语句、重新解析，失败回滚；不修改 Mapper 代理链，只给 `Configuration.addMappedStatement` 注入注册调用，兼容现有 MyBatis-Plus、PageHelper、Flowable 代理。
- **结构热更**：JBR + `-XX:+AllowEnhancedClassRedefinition` 下支持增删方法/字段、增加类注解、增加接口；JBR 21/25 还支持删除/替换接口。替换父类仍不支持，需要重启。
- **无额外 agent**：不需要外部 `hotswap-agent-2.0.5.jar`，不需要 `-XX:HotswapAgent=external`，也不需要 `--add-opens`。
- **IDE 集成**：`javaAgents` 自动覆盖 Application / Spring Boot / JAR Application；保留 Run/Debug 右键；自动编译带防抖，构建后焦点回到编辑器。
- **诊断与兜底**：内置授权/项目 JDK/输出目录诊断、复制增强 VM 参数；HA 启动失败时回退自研 `ClassRedefiner`，方法体和 XML 仍可用。

### 相比 OpenIDEA 自带热更新的补充

OpenIDEA 自带 HotSwap 适合 Debug 下快速改方法体，但在标准 JDK 下它不覆盖：

- **Mapper XML / MyBatis 状态**：不会刷新 mapper XML、清 namespace、更新 `MappedStatement`，也没有解析失败回滚。
- **Spring 上下文 reprocess**：不会在类变化后 reprocess Spring bean、controller、AOP 元数据。
- **结构变更**：标准 JDK 只能替换方法体；不会增删方法/字段、增加注解/接口，也没有 JBR 增强重定义的配合。
- **Mapper 保存后的构建链路**：不会在保存 mapper XML 后触发模块构建、复制资源到 `target/classes` 并刷新 XML。
- **MyBatis-Plus / 代理安全**：不会协调 `MybatisConfiguration`、`MybatisMapperRegistry`、PageHelper、Flowable 的代理链。
- **JBR 诊断**：不会检查当前 JDK 是不是 JBR、增强参数有没有加，也不会解释结构变更为什么失败。

插件补齐这些缺口，同时保留 IDE 自带调试器。不同 OpenIDEA / JDK 版本的自带 HotSwap 行为可能不同，上面列的是本插件专门覆盖的缺口。

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

### MyBatis 集成

#### 为什么 HA 的 MyBatis 插件不能实现 namespace 级回滚和本插件的 MyBatis-Plus 代理兼容

HA 的 MyBatis 插件围绕 `ConfigurationProxy` / `SpringMybatisConfigurationProxy` 设计：它 patch `XMLConfigBuilder`、`XMLMapperBuilder`、`Configuration`、`MapperRegistry`、`SqlSessionFactoryBean`，刷新的是**整个 Configuration**，不是 namespace。它没有 namespace 级快照/事务：解析后如果留下 deferred statement / resultMap，也没有 API 恢复旧 namespace 和 pending parser 状态。这正是本插件需要的回滚能力。

HA 另有独立的 MyBatis-Plus 插件，但它使用 `ConfigurationPlusProxy`，并 patch `MybatisConfiguration`、`MybatisXMLMapperBuilder`、`MybatisSqlSessionFactoryBean`、`TableInfoHelper`。这会再加一层 proxy/注册包装，不是本插件要求的"不改代理链"。HA 的插件可以单独使用，但无法同时保留本插件的 registry 和原有 MyBatis-Plus / PageHelper / Flowable 代理链。

#### 本插件针对 MyBatis 的逻辑

1. **发现真实的 Configuration**：`MyBatisConfigTransformer` 用 ASM 在 `Configuration` 和 MyBatis-Plus 的 `MybatisConfiguration.addMappedStatement` 开头注入一行注册调用，把 `this` 放进 `MyBatisConfigRegistry`（弱引用）。它不碰 `MapperRegistry`、`MapperProxyFactory`、Mapper 接口和拦截器。
2. **按资源找 owner**：`MyBatisXmlReloader` 读取 namespace 后，根据每个 Configuration 的 `loadedResources` 和插件记录的资源归属，只刷新真正加载了这个文件的 Configuration；同名 namespace 的其他文件不会被误刷。
3. **按 namespace 做事务式刷新**：先备份 namespace 在 `mappedStatements`、`resultMaps`、`parameterMaps`、`keyGenerators`、`sqlFragments` 和 cache 中的条目，移除旧条目和 loaded resource，再用 `XMLMapperBuilder` 重新解析。
4. **提交前验证 deferred**：调用 MyBatis 的 pending parser，检查 namespace 对应的 `incompleteStatements` / `incompleteResultMaps` / `incompleteCacheRefs`；有未解析项就拒绝这次结果。
5. **失败回滚 / 成功提交**：失败时恢复备份的 map 和 cache，删除该 namespace 的 pending parser 状态并 unload resource，旧语句继续可用，修正文件后还能重试；成功时保留新条目并记录资源 owner。
6. **不创建代理**：插件从不创建 Mapper 代理，也不包装 `Configuration`；MyBatis-Plus、PageHelper、Flowable 原有的代理/拦截器链保持不变。

#### 同时开启会出现什么问题

- **同一批类有两个 transformer**：HA 的 Javassist transformer 给 `Configuration`、`XMLMapperBuilder`、`MapperRegistry` 等加 proxy/refresh hook，本插件的 ASM transformer 又往 `addMappedStatement` 注入注册调用，字节码修改和初始化顺序不再可控。
- **同一个 namespace 有两个 owner**：两条路径都解析同一份 mapper XML，容易报 `Mapped Statements collection already contains value for ...`，或者出现重复 `resultMap` / `sqlFragment`、namespace 只更新一半。
- **registry 指向错误对象**：HA 的 `ConfigurationProxy` 可能替换应用实际使用的 `Configuration`，而 `MyBatisConfigRegistry` 和 reloader 仍持有原对象。结果可能是日志显示刷新成功、应用查询的却是 proxy，或者反过来。
- **代理链被包两层**：HA 的 MyBatis-Plus `ConfigurationPlusProxy` / `SpringMybatisConfigurationProxy` 叠加在 MyBatis-Plus 的 `MybatisConfiguration` 和已有 Mapper 代理上，PageHelper / Flowable 的 Interceptor 可能被重复挂载、绕过或挂到错误的 Configuration 上。
- **回滚不再一致**：如果 HA 的解析在插件开始之后失败，或插件回滚而 HA 保留新状态，两条路径就会不一致，namespace 可能停留在 issue #1 描述的半坏状态。

所以 agent 启动 HA 时固定传入：

```text
disablePlugin=MyBatis
disablePlugin=MyBatisPlus
disablePlugin=WatchResources
```

HA 的类重定义和 Spring 插件仍然启用。如果去掉本插件的 XML reloader 和 registry，HA 的 MyBatis 插件可以单独使用；但那是另一套集成，不是本插件的一个配置开关。

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

从 OpenIDEA 社区市场安装，或通过 `Settings › Plugins › Install Plugin from Package File…`
安装本地 `.oiplugin`。

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
15:00:05.744 [hotreload] INFO  Hot Reload agent 1.1.1 (pid 65453, OpenJDK 64-Bit Server VM 17.0.14+1-b1367.22)
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

测试工程：`test/fixture-structure`。每个 case 启动一个**全新的 JVM**，修改后重新编译；进程 PID 始终不变。方法体 / Mapper XML 不添加 `-XX`；结构变更只在 JBR 上带 `-XX:+AllowEnhancedClassRedefinition`。以下结果来自 `test/capability.sh` 的直接测试。

### 常规 JDK（非 JBR）

| 操作         | JDK 8    | JDK 11   | JDK 21   | JDK 25   |
| ---------- | -------- | -------- | -------- | -------- |
| 方法体        | ✅        | ✅        | ✅        | ✅        |
| Mapper XML | ✅        | ✅        | ✅        | ✅        |
| 增加方法       | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 |
| 删除方法       | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 |
| 增加字段       | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 |
| 删除字段       | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 |
| 增加类注解      | ✅        | ✅        | ✅        | ✅        |
| 删除类注解      | ✅        | ✅        | ✅        | ✅        |
| 增加接口       | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   |
| 删除接口       | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   |
| 替换接口       | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   |
| 替换父类       | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   | ❌ 层次变更   |
| 修改方法签名     | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 | ❌ JVM 拒绝 |

### JBR（JetBrains Runtime）

| 操作         | JBR 17 | JBR 21 | JBR 25 |
| ---------- | ------ | ------ | ------ |
| 方法体        | ✅      | ✅      | ✅      |
| Mapper XML | ✅      | ✅      | ✅      |
| 增加方法       | ✅      | ✅      | ✅      |
| 删除方法       | ✅      | ✅      | ✅      |
| 增加字段       | ✅      | ✅      | ✅      |
| 删除字段       | ✅      | ✅      | ✅      |
| 增加类注解      | ✅      | ✅      | ✅      |
| 删除类注解      | ✅      | ✅      | ✅      |
| 增加接口       | ✅      | ✅      | ✅      |
| 删除接口       | ❌ 层次变更 | ✅      | ✅      |
| 替换接口       | ❌ 层次变更 | ✅      | ✅      |
| 替换父类       | ❌ 层次变更 | ❌ 层次变更 | ❌ 层次变更 |
| 修改方法签名     | ✅      | ✅      | ✅      |

结论：

- **方法体在 JDK 8–25 上开箱可用**，不需要 `-XX`；Mapper XML 由自研 reloader 处理，也不需要 `-XX`。
- **类注解的增删在标准 JDK 上也可用**（实测 JDK 8 / 11 / 21 / 25 都通过）；JBR 上同样可用。
- **增删方法/字段、修改方法签名**在标准 JDK 上失败；JBR 17 / 21 / 25 都通过。方法签名改变后旧方法被替换，调用方需要按新签名重新编译。
- **增加接口**在 JBR 17 / 21 / 25 都可用；**删除/替换接口**在 JBR 17 上仍失败，在 JBR 21 / 25 上通过。
- **替换父类**在三个 JBR 版本上都失败，需要重启；这是 JVM 的结构限制。
- Mapper XML 解析失败时，agent 会恢复旧 statement 并打印错误，应用继续可用。
- **Spring bean / controller / AOP** 由 HA 2.0.5 的 Spring 插件 reprocess；实测新增 `@GetMapping` 后直接返回 200，不需要重启。
- **Spring XML 配置（`applicationContext.xml`）不热更**：为了保证 mapper XML 不被 HA 当成 Spring bean 定义解析，agent 关闭了 HA 的 `registerResourceListeners`。Java Config / Spring Boot 项目不受影响。
- **MyBatis / MyBatis-Plus 的 HA 插件关闭**：mapper XML 由自研 reloader 处理；mapper 接口本身的增删方法走 HA 类重定义，但 MyBatis 的 mapper 注册是否刷新未单独回归。
- `@Value` / `@ConfigurationProperties` 的重新绑定由 HA 的 Spring 插件尝试处理，未单独回归；新增 Bean 定义通常仍建议重启。
- IDE 的 `javaAgents` 不覆盖 Maven/Gradle goal、JUnit、Tomcat、远程 JVM；这些启动方式如果确实需要，得自己加 `-javaagent`。

### 运行这些能力测试

```bash
# 常规 JDK 或 JBR 都直接传入
JAVA=/path/to/jdk/bin/java ./test/capability.sh
```

每个 case 单独启动 JVM，失败的结构变更不会污染下一个 case。

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

# 延迟解析错误回滚 + 按 Configuration 定向刷新（issue #1 回归）
./test/reloader-regression.sh

# 按操作类型逐个测试；常规 JDK 或 JBR 都可用
JAVA=/path/to/jdk/bin/java ./test/capability.sh

# 对每个已安装 JDK 分别跑 e2e/structure
./test/jdk-matrix.sh
```

`structure.sh` 分成三个独立 JVM 组，失败的重定义不会污染下一组；`reloader-regression.sh` 覆盖 issue #1 的延迟错误回滚和按 Configuration 定向刷新；完整矩阵由 `test/jdk-matrix.sh` 生成。

`test/jdk-matrix.sh` 默认用 `/usr/libexec/java_home -V` 发现的 JDK；也可以指定：

```bash
# 也可以把下载的 JBR 目录交给脚本
JBR_MATRIX_DIR=/path/to/jbr ./test/jdk-matrix.sh

# 或显式指定所有 JDK
JDK_MATRIX_HOMES="$JAVA8:$JAVA11:$JBR17:$JBR21:$JBR25:$JAVA21:$JAVA25" \
  ./test/jdk-matrix.sh
```

`test/jdk-matrix.sh` 每次运行都会重新生成下面的“脚本覆盖矩阵”；按操作类型的真实能力以“能力边界”的两张表为准：

<!-- BEGIN JDK-MATRIX -->
<!-- generated by test/jdk-matrix.sh; do not edit by hand -->
| 修改         | JDK 8        | JDK 11       | JBR 17    | JBR 21    | JDK 21       | JBR 25    | JDK 25       |
| ---------- | ------------ | ------------ | --------- | --------- | ------------ | --------- | ------------ |
| 方法体        | ✅            | ✅            | ✅         | ✅         | ✅            | ✅         | ✅            |
| Mapper XML | ✅            | ✅            | ✅         | ✅         | ✅            | ✅         | ✅            |
| 增加方法/字段    | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅         | ✅         | ❌ 未测试（需 JBR） | ✅         | ❌ 未测试（需 JBR） |
| 增加类注解      | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅         | ✅         | ❌ 未测试（需 JBR） | ✅         | ❌ 未测试（需 JBR） |
| 增加接口       | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅         | ✅         | ❌ 未测试（需 JBR） | ✅         | ❌ 未测试（需 JBR） |
| 删除方法/字段    | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ✅         | ✅         | ❌ 未测试（需 JBR） | ✅         | ❌ 未测试（需 JBR） |
| 删除接口       | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ✅         | ❌ 未测试（需 JBR） | ✅         | ❌ 未测试（需 JBR） |
| 替换接口       | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ✅         | ❌ 未测试（需 JBR） | ✅         | ❌ 未测试（需 JBR） |
| 替换父类       | ❌ 未测试（需 JBR） | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ❌ 层次变更不支持 | ❌ 未测试（需 JBR） | ❌ 层次变更不支持 | ❌ 未测试（需 JBR） |
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
