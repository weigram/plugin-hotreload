# Hot Reload for OpenIDEA

English | [简体中文](README.zh-CN.md)

Java and MyBatis XML hot reload for OpenIDEA. Save a Java file or a MyBatis mapper XML and the plugin builds the module and reloads classes or mapper statements in the running JVM — no application restart. The implementation approach references the [HotSwapHelper](https://github.com/gejun123456/HotSwapHelper) project.

The class-redefinition and Spring engine is [HotSwapAgent 2.0.5](https://github.com/HotswapProjects/HotswapAgent). This project adds the OpenIDEA integration: `javaAgents` injection, MyBatis XML reload, JDK module opening, safe Spring XML handling, and the IDE build loop.

Plugin ID: `weigram.hotreload`

## 1. Features

- **Method-body hot reload** on JDK 8–25, in both **Run** and **Debug** configurations.
- **MyBatis / MyBatis-Plus mapper XML reload** through a custom namespace reloader. The HA MyBatis plugins are disabled so mapper XML is not parsed twice.
- **Spring bean / controller / AOP reprocessing** through HotSwapAgent's Spring plugin.
- **Add/remove methods and fields, add class annotations, add interfaces** on JetBrains Runtime with `-XX:+AllowEnhancedClassRedefinition`.
- **Automatic build on save**, with debounce and an option to return focus to the editor after the Build window opens.
- **No external HotSwapAgent jar** and no `-XX:HotswapAgent=external`.
- **No `--add-opens`** for the application: the agent opens the JDK packages it needs at runtime.
- **Fallback redefiner** when HotSwapAgent cannot start: method bodies and XML still work, but Spring reprocessing is unavailable.

### What this adds beyond OpenIDEA's built-in HotSwap

OpenIDEA's built-in HotSwap is useful for quick method-body edits, but in a standard JDK debug session it does not cover:

- **Mapper XML / MyBatis state.** It does not reload mapper XML, clear a namespace, refresh `MappedStatement`s, or roll back a failed mapper parse.
- **Spring context reprocessing.** It does not reprocess Spring bean definitions, controllers, or AOP metadata after a class change.
- **Structural changes.** With a standard JDK it only replaces method bodies; it does not add/remove methods or fields, add annotations/interfaces, or handle JBR-enhanced changes.
- **Build loop for mapper saves.** It does not connect saving a mapper XML to a module build, a resource copy into `target/classes`, and an XML refresh.
- **MyBatis-Plus / proxy safety.** It does not coordinate with `MybatisConfiguration`, `MybatisMapperRegistry`, PageHelper, or Flowable proxy chains.
- **JBR diagnostics.** It does not check whether the selected JDK is JBR, whether the enhanced-redefinition flag is present, or explain why a structural change failed.

This plugin fills those gaps while leaving the IDE debugger usable. Built-in HotSwap behavior varies by OpenIDEA and JDK version; the list above is the set of gaps this integration is designed to cover.

Structural changes have JVM limits. The tested matrix is in [Test Status](#4-test-status); replacing a superclass is not supported by JBR 17/21/25, and replacing/removing interfaces works on JBR 21/25 but not JBR 17.

## 2. How It Works

1. **OpenIDEA injects the agent.** `plugin.toml` declares `[[contributes.javaAgents]]`; OpenIDEA adds `-javaagent:.../hotreload-agent.jar` to Application, Spring Boot, and JAR Application configurations, for Run and Debug.
2. **The agent starts the engines.** It opens JDK modules, registers the MyBatis transformer, patches HA's Spring XML resource listener, and starts HotSwapAgent 2.0.5 by calling `HotswapAgent.premain` directly.
3. **HotSwapAgent owns class reloading and Spring.** It handles class redefinition and the Spring bean/controller/AOP reprocessing. The plugin disables HA's `MyBatis`, `MyBatisPlus`, and `WatchResources` plugins.
4. **The plugin owns mapper XML.** A custom watcher detects changed mapper XML, clears the old namespace entries, reparses with MyBatis's `XMLMapperBuilder`, and rolls back on parse failure.
5. **Saving a file triggers the IDE build.** The plugin invokes `build.buildModule`; the agent watches `target/classes`, HotSwapAgent reloads changed classes, and the XML reloader refreshes mapper statements. The application PID does not change.
6. **Fallback.** If HotSwapAgent or the module opens fail, the plugin's `ClassRedefiner` still handles method bodies and XML; Spring reprocessing is skipped.

### Inside `hotswap-agent-2.0.5.jar`

The HotSwapAgent jar is not started as a second agent. `agent/package.py` unpacks it and merges it, together with ASM and the plugin's classes, into `agent/hotreload-agent.jar`; the merged jar replaces HA's manifest and default `hotswap-agent.properties`.

- **Bootstrap.** The plugin calls `HotswapAgent.premain(args, instrumentation)`. HA creates its `PluginManager` and `PluginRegistry`, loads `hotswap-agent.properties`, and initializes the enabled plugins.
- **Plugin discovery.** `PluginRegistry` scans HA plugins such as `Spring` and `SpringBoot`. Names passed through `disablePlugin` are skipped; this plugin disables `MyBatis`, `MyBatisPlus`, and `WatchResources`.
- **Watching.** With `autoHotswap=true`, `TreeWatcherNIO` watches class output directories. `HotswapperPlugin` receives changed `.class` files and forwards them to `PluginManager`.
- **Redefinition.** `PluginManager.hotswap` calls `Instrumentation.redefineClasses`. HA logs `RELOAD` on success and the JVM exception on failure. HA cannot exceed JVM limits: standard HotSpot accepts method-body changes; structural changes still need JBR and `-XX:+AllowEnhancedClassRedefinition`.
- **Framework reprocessing.** HA's Spring and Spring Boot plugins observe class/context changes and reprocess Spring bean definitions, controllers, and AOP metadata after class reload.
- **Configuration.** The plugin passes `autoHotswap=true`, `disablePlugin=MyBatis`, `disablePlugin=MyBatisPlus`, `disablePlugin=WatchResources`, and `LOGGER=info`. It does not use `-XX:HotswapAgent=external`.
- **License.** HA 2.0.5 is GPL-2.0 and is kept unmodified inside the merged jar; the plugin's own code and the HA jar are both distributed under GPL-2.0.

### MyBatis integration

#### Why HA's MyBatis plugin cannot provide namespace-level rollback or this plugin's MyBatis-Plus proxy compatibility

HA's MyBatis plugin is built around `ConfigurationProxy` / `SpringMybatisConfigurationProxy`. It patches `XMLConfigBuilder`, `XMLMapperBuilder`, `Configuration`, `MapperRegistry`, and `SqlSessionFactoryBean`, then refreshes a *configuration*, not a namespace. It has no per-namespace snapshot or transaction: if a parse leaves deferred statements/result maps behind, there is no API to restore the previous namespace and its pending parser state. That is exactly the rollback guarantee this plugin needs.

HA also ships a separate MyBatis-Plus plugin, but it uses `ConfigurationPlusProxy` and patches `MybatisConfiguration`, `MybatisXMLMapperBuilder`, `MybatisSqlSessionFactoryBean`, and `TableInfoHelper`. That adds another proxy/registration layer; it is not the no-proxy behavior this plugin guarantees. The HA plugin may be usable on its own, but it cannot preserve both this plugin's registry and the application's existing MyBatis-Plus / PageHelper / Flowable proxy chain.

#### How this plugin handles MyBatis

1. **Discover the real Configuration.** `MyBatisConfigTransformer` uses ASM to inject one call at the top of `addMappedStatement` in both `org.apache.ibatis.session.Configuration` and MyBatis-Plus's `MybatisConfiguration`. The call registers `this` in `MyBatisConfigRegistry`, a list of weak references. The transformer does not touch `MapperRegistry`, `MapperProxyFactory`, mapper interfaces, or interceptors.
2. **Find the owner of the changed XML.** `MyBatisXmlReloader` reads the namespace, then looks at the `loadedResources` of each live configuration and its own recorded resource ownership. It reloads only the configuration(s) that actually loaded that resource; a same-namespace configuration with a different file is left alone.
3. **Reload one namespace as a transaction.** For each target it snapshots the namespace entries in `mappedStatements`, `resultMaps`, `parameterMaps`, `keyGenerators`, `sqlFragments`, and the namespace cache, removes the old entries and the loaded resource, then reparses with `XMLMapperBuilder`.
4. **Validate deferred work before committing.** It calls MyBatis's pending parsers and checks `incompleteStatements` / `incompleteResultMaps` / `incompleteCacheRefs` for the namespace. If anything is unresolved, the parsed result is rejected.
5. **Rollback or accept.** On failure it restores the snapshotted maps and cache, drops the failed namespace's pending parser entries, and unloads the resource, so the old statement keeps working and a corrected file can be retried. On success it keeps the new entries and records the resource owner.
6. **Keep the proxy chain intact.** The plugin never creates a mapper proxy and never wraps `Configuration`; MyBatis-Plus, PageHelper, and Flowable keep the proxy/interceptor chain they built.

#### What happens if both are enabled

- **Two transformers on the same classes.** HA's Javassist transformers add proxy/refresh hooks to `Configuration`, `XMLMapperBuilder`, `MapperRegistry`, and related classes while this plugin's ASM transformer injects `addMappedStatement` registration. The resulting bytecode and initialization order are no longer deterministic.
- **Two owners for one namespace.** Both paths parse the same mapper XML and add statements. A typical failure is `Mapped Statements collection already contains value for ...`, duplicate `resultMap`/`sqlFragment`, or a half-updated namespace.
- **The registry points at the wrong object.** HA's `ConfigurationProxy` can replace the `Configuration` used by the application; `MyBatisConfigRegistry` and the reloader still hold the original object. A reload can report success while the application queries the proxy, or vice versa.
- **Proxy chains get wrapped twice.** HA's MyBatis-Plus `ConfigurationPlusProxy` / `SpringMybatisConfigurationProxy` is layered on MyBatis-Plus's `MybatisConfiguration` and the existing mapper proxies. PageHelper or Flowable interceptors can end up duplicated, bypassed, or attached to the wrong configuration.
- **Rollback is no longer coordinated.** If HA's parser fails after this plugin has started, or this plugin rolls back while HA keeps its new state, the two paths disagree. The failed namespace can be left in the half-broken state issue #1 describes.

For these reasons the agent starts HA with:

```text
disablePlugin=MyBatis
disablePlugin=MyBatisPlus
disablePlugin=WatchResources
```

HA class redefinition and the Spring plugin remain enabled. If the custom XML reloader and registry were removed, HA's MyBatis plugin could be used on its own — that is a different integration, not a configuration toggle for this one.

```text
save file
   |
   v
OpenIDEA build.buildModule
   |
   v
target/classes changed
   |
   v
HA TreeWatcherNIO -> HotswapperPlugin -> PluginManager
   |
   v
Instrumentation.redefineClasses
   |
   v
HA Spring plugin reprocesses beans/controllers/AOP
```

## 3. Configuration

### Install

Install from the OpenIDEA marketplace, or use:

```text
Settings › Plugins › Install Plugin from Package File…
```

### Capabilities

| Capability     | Risk             | Why it is needed                                                                                  |
| -------------- | ---------------- | ------------------------------------------------------------------------------------------------- |
| `java.agent`   | high             | Runs the hot-reload agent inside the application JVM. OpenIDEA records the jar hash when granted. |
| `ide.commands` | high             | Calls the IDE build action and, after automatic builds, returns focus to the editor.              |
| `process:mvn`  | optional, high   | Manual Maven fallback when `buildBackend = maven`. Automatic builds use the IDE build.            |
| `clipboard`    | optional, medium | Used by **Copy Enhanced VM Options**.                                                             |
| `events`       | low              | Watches saved files.                                                                              |
| `project.read` | low              | Reads project build and Java configuration.                                                       |

There is no network capability and no telemetry.

### Settings

| Setting                         | Default                  | Description                                                                                                  |
| ------------------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------ |
| Auto compile on save            | on                       | Save a Java/Mapper XML file and trigger the IDE build. Manual commands still work when off.                  |
| Build on XML save               | on                       | Mapper XML must be copied to `target/classes` before the agent can see it.                                   |
| Build backend                   | `ide`                    | `ide` uses `build.buildModule`. `maven` affects only manual commands; automatic builds stay on the IDE path. |
| Maven compile arguments         | `-q -DskipTests compile` | Used by manual Maven builds.                                                                                 |
| Auto-compile debounce (ms)      | `1200`                   | Coalesces consecutive saves into one build.                                                                  |
| Refocus editor after auto build | on                       | The Build window still opens, but focus returns to the editor.                                               |
| Notifications                   | on                       | Errors and missing grants. Normal reload logs stay in the application Run console.                           |

### Structural changes

Structural changes require **JetBrains Runtime (JBR)** and this VM option:

```text
-XX:+AllowEnhancedClassRedefinition
```

Put it in the run configuration's **VM options** or in:

```text
Edit Configurations → Edit configuration templates → Application / Spring Boot → VM options
```

The plugin **does not choose or download JBR** and cannot modify the native run configuration's JRE or VM options. Select JBR as the project/run JDK yourself; the plugin only injects the agent. A standard JDK rejects the option with `Unrecognized VM option`.

### Commands

| Command                                | Shortcut         | Purpose                                                              |
| -------------------------------------- | ---------------- | -------------------------------------------------------------------- |
| `Hot Reload: Run Current File`         | —                | Start the current file with the IDE context run configuration (Run). |
| `Hot Reload: Debug Current File`       | —                | Same, with the debugger.                                             |
| `Hot Reload: Compile & Reload`         | ⌘⌥R / Ctrl+Alt+R | Compile the current module immediately.                              |
| `Hot Reload: Diagnose`                 | —                | Show grants, JDK, output directories, VM options, and log guidance.  |
| `Hot Reload: Copy Enhanced VM Options` | —                | Copy `-XX:+AllowEnhancedClassRedefinition`.                          |

The editor, editor-tab, and project-tree context menus contain only **Run** and **Debug**.

### Notes

- Do **not** add a second external `hotswap-agent-2.0.5.jar` or `-XX:HotswapAgent=external`.
- Do **not** add `--add-opens` for the application; the agent opens the required JDK packages.
- If the project uses Maven resource filtering (`@nacos.*@`), run `mvn process-resources` once so `target/classes/application.yml` contains resolved values.
- Spring XML bean definitions are not hot-reloaded; Java Config / Spring Boot configuration is the supported path.

## 4. Test Status

The matrix is regenerated by `test/jdk-matrix.sh`; do not edit it by hand.

<!-- BEGIN JDK-MATRIX -->
<!-- generated by test/jdk-matrix.sh; do not edit by hand -->
| Modification | JDK 8 | JDK 11 | JBR 17 | JBR 21 | JDK 21 | JBR 25 | JDK 25 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Method body | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mapper XML | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Add method/field | ❌ not tested (JBR required) | ❌ not tested (JBR required) | ✅ | ✅ | ❌ not tested (JBR required) | ✅ | ❌ not tested (JBR required) |
| Add class annotation | ❌ not tested (JBR required) | ❌ not tested (JBR required) | ✅ | ✅ | ❌ not tested (JBR required) | ✅ | ❌ not tested (JBR required) |
| Add interface | ❌ not tested (JBR required) | ❌ not tested (JBR required) | ✅ | ✅ | ❌ not tested (JBR required) | ✅ | ❌ not tested (JBR required) |
| Remove method/field | ❌ not tested (JBR required) | ❌ not tested (JBR required) | ✅ | ✅ | ❌ not tested (JBR required) | ✅ | ❌ not tested (JBR required) |
| Remove interface | ❌ not tested (JBR required) | ❌ not tested (JBR required) | ❌ hierarchy change unsupported | ✅ | ❌ not tested (JBR required) | ✅ | ❌ not tested (JBR required) |
| Replace interface | ❌ not tested (JBR required) | ❌ not tested (JBR required) | ❌ hierarchy change unsupported | ✅ | ❌ not tested (JBR required) | ✅ | ❌ not tested (JBR required) |
| Replace superclass | ❌ not tested (JBR required) | ❌ not tested (JBR required) | ❌ hierarchy change unsupported | ❌ hierarchy change unsupported | ❌ not tested (JBR required) | ❌ hierarchy change unsupported | ❌ not tested (JBR required) |
<!-- END JDK-MATRIX -->

Legend: ✅ passes; ❌ fails or was not tested. **Method body / Mapper XML** run without `-XX:+AllowEnhancedClassRedefinition`. **Structural changes** run only on JBR with that option and are skipped on standard JDKs.

JBR differences observed:

- JBR 17: add/remove methods and fields, add annotations, add interfaces; removing/replacing interfaces fails; replacing a superclass fails.
- JBR 21 and 25: removing and replacing interfaces also pass; replacing a superclass still fails.
- JDK 8 / 11 / 21 / 25: method body, Mapper XML, and add/remove class annotations pass; other structural changes fail. The generated matrix above marks some of these as "not tested" because `jdk-matrix.sh` does not run the full structural probe on standard JDKs; the per-operation tables below are the authoritative results.

### Per-operation capability

Each case starts a fresh JVM. Method body and Mapper XML do not use `-XX`; structural JBR cases use `-XX:+AllowEnhancedClassRedefinition`.

#### Standard JDK

| Operation | JDK 8 | JDK 11 | JDK 21 | JDK 25 |
| --- | --- | --- | --- | --- |
| Method body | ✅ | ✅ | ✅ | ✅ |
| Mapper XML | ✅ | ✅ | ✅ | ✅ |
| Add method | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses |
| Remove method | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses |
| Add field | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses |
| Remove field | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses |
| Add class annotation | ✅ | ✅ | ✅ | ✅ |
| Remove class annotation | ✅ | ✅ | ✅ | ✅ |
| Add interface | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change |
| Remove interface | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change |
| Replace interface | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change |
| Replace superclass | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change |
| Change method signature | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses | ❌ JVM refuses |

#### JBR

| Operation | JBR 17 | JBR 21 | JBR 25 |
| --- | --- | --- | --- |
| Method body | ✅ | ✅ | ✅ |
| Mapper XML | ✅ | ✅ | ✅ |
| Add method | ✅ | ✅ | ✅ |
| Remove method | ✅ | ✅ | ✅ |
| Add field | ✅ | ✅ | ✅ |
| Remove field | ✅ | ✅ | ✅ |
| Add class annotation | ✅ | ✅ | ✅ |
| Remove class annotation | ✅ | ✅ | ✅ |
| Add interface | ✅ | ✅ | ✅ |
| Remove interface | ❌ hierarchy change | ✅ | ✅ |
| Replace interface | ❌ hierarchy change | ✅ | ✅ |
| Replace superclass | ❌ hierarchy change | ❌ hierarchy change | ❌ hierarchy change |
| Change method signature | ✅ | ✅ | ✅ |

#### DCEVM

| Operation | DCEVM 8u282 | DCEVM 11.0.15+1 |
| --- | --- | --- |
| Method body | ✅ | ✅ |
| Mapper XML | ✅ | ✅ |
| Add method | ✅ | ✅ |
| Remove method | ✅ | ✅ |
| Add field | ✅ | ✅ |
| Remove field | ✅ | ✅ |
| Add class annotation | ✅ | ✅ |
| Remove class annotation | ✅ | ✅ |
| Add interface | ✅ | ✅ |
| Remove interface | ❌ hierarchy change | ❌ hierarchy change |
| Replace interface | ❌ hierarchy change | ❌ hierarchy change |
| Replace superclass | ❌ hierarchy change | ❌ hierarchy change |
| Change method signature | ✅ | ✅ |

DCEVM 8u282 and 11.0.15+1 are macOS x86_64 builds; they were run on Apple Silicon through Rosetta. Their structural behavior matches JBR 17: add/remove methods and fields, annotations, add interfaces, and method-signature changes pass; removing/replacing interfaces and replacing a superclass fail.

Test commands:

```bash
./test/e2e.sh

JAVA=/path/to/jbr/bin/java ./test/structure.sh

# deferred XML error rollback + per-Configuration targeting
./test/reloader-regression.sh

# per-operation probe; use a JDK, JBR, or DCEVM
JAVA=/path/to/jdk/bin/java ./test/capability.sh
JAVA=/path/to/dcevm/bin/java ./test/capability.sh

JBR_MATRIX_DIR=/path/to/jbr ./test/jdk-matrix.sh
```

`test/e2e.sh` covers Mapper XML and method-body redefinition. `test/structure.sh` runs the structural matrix in isolated JVMs. `test/reloader-regression.sh` covers issue #1: deferred-error rollback and per-Configuration targeting. `test/capability.sh` probes each operation in a fresh JVM. `test/jdk-matrix.sh` runs both against every JDK and rewrites the generated table in `README.md` and `README.zh-CN.md`.

### Direct OpenIDEA test

OpenIDEA 0.1.48 was tested with the linked plugin, a JBR 17 run configuration, and:

```text
-XX:+AllowEnhancedClassRedefinition
```

The Run console command contained `-javaagent:.../hotreload-agent.jar`, confirming `javaAgents` injection. Method-body changes, adding methods/fields/annotations, and adding an interface worked through IDE auto-build. Removing/replacing interfaces and replacing a superclass failed with `attempted to change superclass or interfaces`; after a failed hierarchy change, the application must be restarted before further structural changes are reliable.

## 5. Build and License

```bash
./build.sh
openidea-plugin check
```

Builds require Rust with the `wasm32-wasip2` target, `openidea-plugin`, JDK 9+ for the agent, and Python 3.

License: GPL-2.0-only. Bundled [HotSwapAgent 2.0.5](https://github.com/HotswapProjects/HotswapAgent) is GPL-2.0; ASM 9.8 is BSD-3-Clause. See `LICENSE` and `agent/lib/README.md`.
