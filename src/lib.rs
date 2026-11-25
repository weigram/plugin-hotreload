//! Hot Reload — Java / MyBatis XML hot reload for OpenIDEA.
//!
//! The plugin has two halves:
//!
//! * `[[contributes.javaAgents]]` in `plugin.toml` makes the IDE add the
//!   bundled agent to Application, Spring Boot and JAR Application run
//!   configurations (Run and Debug). The agent redefines changed classes and
//!   re-parses changed MyBatis mapper XML in the running JVM.
//! * This component watches `files-changed`, triggers the IDE's incremental
//!   build for the module of the saved file, and provides the commands and
//!   diagnostics. Without it the agent still works after a manual build; the
//!   component exists so the user does not have to press Build on every save.

use std::time::{SystemTime, UNIX_EPOCH};

use openidea_plugin::host::Command;
use openidea_plugin::{
    host, register_plugin, ContentTab, Context, Error, Event, EventKind, FilesChanged, Invocation,
    LogLevel, NotificationLevel, Plugin, Project, StatusItem,
};

const STATUS_ID: &str = "weigram.hotreload.status";
/// The IDE registers an executor action per plugin: `pluginExecutor.<id>.run`.
/// The manifest id prefixes the action; the short id is the fallback.
const EXECUTOR_ACTIONS: [&str; 2] = ["pluginExecutor.weigram.hotreload", "pluginExecutor.hotreload"];
const ENHANCED_FLAG: &str = "-XX:+AllowEnhancedClassRedefinition";
/// View id of the Services list, and the prefix of its item keys.

#[derive(Default)]
struct HotReload {
    capabilities: Vec<String>,
    last_build_ms: u64,
    warned_missing_permission: bool,
}

impl Plugin for HotReload {
    fn activate(&mut self, ctx: &Context) -> Result<(), Error> {
        self.capabilities = ctx.capabilities.clone();
        if let Err(error) = host::subscribe(EventKind::FilesChanged) {
            host::log(
                LogLevel::Warn,
                &format!("Hot Reload: cannot subscribe to files-changed: {error}"),
            );
        }
        self.set_status("Hot Reload", "保存 Java / Mapper XML 后自动编译并热更新；点击立即编译");
        Ok(())
    }

    fn run_command(
        &mut self,
        id: &str,
        ctx: &Invocation,
        _arg: Option<&str>,
    ) -> Result<Option<String>, Error> {
        match id {
            "compile" => self.compile(ctx),
            "run-context" => self.run_context(ctx, false),
            "debug-context" => self.run_context(ctx, true),
            "run-service" => self.run_executor(_arg, false),
            "debug-service" => self.run_executor(_arg, true),
            "doctor" => self.doctor(ctx),
            "copy-vm" => self.copy_vm(),
            other => Err(Error::NotFound(format!("command {other}"))),
        }
    }

    fn handle_events(&mut self, events: &[Event], ctx: &Invocation) {
        for event in events {
            if let Event::FilesChanged(changes) = event {
                if let Err(error) = self.on_files_changed(changes, ctx) {
                    host::log(LogLevel::Warn, &format!("Hot Reload: {error}"));
                }
            }
        }
    }
}

impl HotReload {
    fn compile(&mut self, ctx: &Invocation) -> Result<Option<String>, Error> {
        let project = ctx.project().ok_or("请先打开一个项目")?;
        let target = preferred_target(ctx).or_else(|| project.root().ok());
        let Some(target) = target else {
            return Err(Error::Failed("找不到可编译的文件或项目根目录".into()));
        };
        self.trigger_build(&target, ctx, false)?;
        Ok(None)
    }

    fn on_files_changed(&mut self, changes: &FilesChanged, ctx: &Invocation) -> Result<(), Error> {
        if !host::setting_bool("autoCompile").unwrap_or(true) {
            return Ok(());
        }
        let include_xml = host::setting_bool("includeXml").unwrap_or(true);

        let mut java = None;
        let mut other = None;
        for path in changes.created.iter().chain(changes.modified.iter()) {
            if !is_source_change(path, include_xml) {
                continue;
            }
            let lower = path.to_ascii_lowercase();
            if lower.ends_with(".java") {
                java = Some(path.clone());
            } else if other.is_none() {
                other = Some(path.clone());
            }
        }
        let Some(target) = java.or(other) else {
            return Ok(());
        };

        let debounce = host::setting_number("debounceMs")
            .unwrap_or(1200.0)
            .clamp(0.0, 30_000.0) as u64;
        let now = now_ms();
        if self.last_build_ms != 0 && now.saturating_sub(self.last_build_ms) < debounce {
            return Ok(());
        }
        self.last_build_ms = now;

        self.trigger_build(&target, ctx, true)
    }

    /// Right-click Run/Debug with Hot Reload in the editor or project tree:
    /// opens the selected file and starts the executor for its saved
    /// configuration.
    fn run_context(&mut self, ctx: &Invocation, debug: bool) -> Result<Option<String>, Error> {
        let selected = ctx
            .selected_paths()
            .into_iter()
            .find(|path| is_source_change(path, true))
            .or_else(|| ctx.editor().map(|editor| editor.path()));
        let Some(path) = selected else {
            return Err(Error::Failed("没有选中文件".into()));
        };
        let active = ctx.editor().map(|editor| editor.path());
        if active.as_deref() != Some(path.as_str()) {
            host::open_location(&path, 0, 0)?;
        }
        match config_id_for_file(ctx, &path)? {
            Some(id) => self.run_executor(Some(&id), debug),
            None => {
                self.warn(
                    "Hot Reload · 没有保存的运行配置",
                    "这个文件没有对应的运行配置。用 Services 里的 Hot Reload: Run，或 IDE 的 More Run/Debug → Run '…' with Hot Reload。",
                );
                Ok(None)
            }
        }
    }

    /// Starts `pluginExecutor.<plugin id>.run|debug` with `arg`: a
    /// configuration id, or the Services argument as it came.
    fn run_executor(&mut self, arg: Option<&str>, debug: bool) -> Result<Option<String>, Error> {
        if !self.has("ide.commands") {
            self.warn_missing_commands();
            return Err(Error::Denied("ide.commands".into()));
        }
        let suffix = if debug { "debug" } else { "run" };
        let mut last: Option<Error> = None;
        for base in EXECUTOR_ACTIONS {
            match host::execute_command(&format!("{base}.{suffix}"), arg) {
                Ok(_) => {
                    self.set_status("Hot Reload · Run", "已通过 Hot Reload executor 启动");
                    return Ok(None);
                }
                Err(Error::Denied(_)) => {
                    self.warn_missing_commands();
                    return Err(Error::Denied("ide.commands".into()));
                }
                Err(error) => last = Some(error),
            }
        }
        let error = last.unwrap_or_else(|| Error::Failed("找不到 Hot Reload executor".into()));
        self.warn(
            "Hot Reload · 无法启动",
            &format!("OpenIDEA 0.1.55 起支持从命令启动 executor；当前 IDE 或插件版本不匹配：{error}"),
        );
        Err(error)
    }

    /// Builds the module of `target`. The IDE action is the default: it is
    /// incremental, runs the project's own build tool, copies resources
    /// (mapper XML included) and reports to the Build window.
    fn trigger_build(
        &mut self,
        target: &str,
        ctx: &Invocation,
        automatic: bool,
    ) -> Result<(), Error> {
        let backend = host::setting_string("buildBackend").unwrap_or_else(|| "ide".to_string());
        if automatic && backend == "maven" {
            // A nested `mvn` process for every save would be slow and the
            // plugin call has a five second budget; the IDE build is the
            // automatic path. `maven` still works for the manual command.
            if !self.warned_missing_permission {
                self.warned_missing_permission = true;
                host::log(
                    LogLevel::Info,
                    "Hot Reload: automatic builds always use the IDE build; `buildBackend = maven` applies to the manual command",
                );
            }
        }

        if backend == "maven" && !automatic {
            return self.maven_build(ctx);
        }

        if !self.has("ide.commands") {
            self.warn_missing_commands();
            return Err(Error::Denied("ide.commands".into()));
        }

        match host::execute_command("build.buildModule", Some(target)) {
            Ok(_) => {
                self.set_status(
                    "Hot Reload",
                    &format!("已触发编译：{}", short_name(target)),
                );
                if automatic && host::setting_bool("refocusEditor").unwrap_or(true) {
                    // `build.buildModule` uses its default `activate = true`,
                    // which shows the Build tool window. The plugin command
                    // bridge only passes a string argument, so it cannot pass
                    // the action's `{ "activate": false }` option. Return focus
                    // to the editor as soon as the build has been started.
                    let _ = host::execute_command("toolwindow.focusEditor", None);
                }
                if !automatic && host::setting_bool("notifications").unwrap_or(true) {
                    host::notify(
                        NotificationLevel::Info,
                        "Hot Reload",
                        "已触发 IDE 编译。agent 看到 target/classes 变化后会热更新，日志在应用的 Run 控制台（[hotreload] 开头）。",
                    );
                }
                Ok(())
            }
            Err(Error::Denied(_)) => {
                self.warn_missing_commands();
                Err(Error::Denied("ide.commands".into()))
            }
            Err(error) => {
                self.warn(
                    "Hot Reload · 编译失败",
                    &format!("无法触发 IDE 构建：{error}"),
                );
                Err(error)
            }
        }
    }

    fn maven_build(&self, ctx: &Invocation) -> Result<(), Error> {
        let project = ctx.project().ok_or("请先打开一个项目")?;
        let configured = host::setting_string("compileArgs")
            .unwrap_or_else(|| "-q -DskipTests compile".to_string());
        let configured = configured.trim();
        let arguments = if configured.is_empty() {
            "-q -DskipTests compile"
        } else {
            configured
        };
        let args: Vec<String> = arguments.split_whitespace().map(str::to_string).collect();
        let command = Command {
            program: "mvn".to_string(),
            args,
            env: Vec::new(),
        };
        // A terminal session instead of `run_process`: a plugin call has a
        // five second budget, while a Maven build may take a minute, and the
        // user can watch (and stop) the build in the terminal tab.
        host::open_terminal_session("hotreload-build", "Hot Reload Build", &command)?;
        let root = project.root().unwrap_or_default();
        if host::setting_bool("notifications").unwrap_or(true) {
            host::notify(
                NotificationLevel::Info,
                "Hot Reload",
                &format!(
                    "已在终端启动 mvn compile（{root}）。agent 会在 class / XML 变化后热更新。"
                ),
            );
        }
        Ok(())
    }

    fn doctor(&mut self, ctx: &Invocation) -> Result<Option<String>, Error> {
        let project = ctx.project();
        let root = project.as_ref().and_then(|p| p.root().ok());
        let java = project.as_ref().and_then(|p| detect_java(p));
        let run_config = root
            .as_deref()
            .map(|root| format!("{root}/.openidea/run.toml"))
            .and_then(|path| project.as_ref().and_then(|p| p.read_file(&path).ok()));

        let mut lines: Vec<String> = Vec::new();
        lines.push("# Hot Reload 诊断".to_string());
        lines.push(String::new());
        lines.push(format!("- 插件版本：{}", env!("CARGO_PKG_VERSION")));
        lines.push(format!(
            "- 自动编译：{}",
            if host::setting_bool("autoCompile").unwrap_or(true) {
                "开"
            } else {
                "关"
            }
        ));
        lines.push(format!(
            "- XML 保存也触发构建：{}",
            if host::setting_bool("includeXml").unwrap_or(true) {
                "开"
            } else {
                "关"
            }
        ));
        lines.push(format!(
            "- 编译方式：{}",
            host::setting_string("buildBackend").unwrap_or_else(|| "ide".to_string())
        ));
        lines.push(format!(
            "- 授权：project.read={} · events={} · ide.commands={} · java.agent={} · clipboard={}",
            yes(self.has("project.read")),
            yes(self.has("events")),
            yes(self.has("ide.commands")),
            yes(self.has("java.agent")),
            yes(self.has("clipboard"))
        ));
        match java {
            Some((major, origin)) => lines.push(format!("- 项目 Java：{major}（{origin}）")),
            None => lines.push("- 项目 Java：未识别（pom.xml / build.gradle 里没有版本声明）".to_string()),
        }
        if let (Some(project), Some(root)) = (project.as_ref(), root.as_deref()) {
            let mut outputs = Vec::new();
            for candidate in [
                "target/classes",
                "build/classes/java/main",
                "out/production/classes",
            ] {
                if project.exists(&format!("{root}/{candidate}")).unwrap_or(false) {
                    outputs.push(candidate);
                }
            }
            lines.push(format!(
                "- 编译输出：{}",
                if outputs.is_empty() {
                    "未找到（先构建一次，或检查构建工具）".to_string()
                } else {
                    outputs.join("、")
                }
            ));
        }
        if let Some(text) = &run_config {
            let has_flag = text.contains("AllowEnhancedClassRedefinition");
            lines.push(format!(
                "- 运行配置里手写的 {}：{}",
                ENHANCED_FLAG,
                if has_flag { "有" } else { "没有" }
            ));
            let commands = run_configs(text);
            let bare = commands
                .iter()
                .filter(|config| !config.main_class.is_empty())
                .filter(|config| is_bare_command(&config.command))
                .count();
            let java_configs = commands.iter().filter(|config| !config.main_class.is_empty()).count();
            if java_configs == 0 {
                lines.push("- 自动添加：没有可识别的 Java 配置".to_string());
            } else if bare == 0 {
                lines.push(
                    "- 自动添加：配置的 command 是绝对路径，IDE 能直接看到 JBR，会按插件声明加上该参数"
                        .to_string(),
                );
            } else {
                lines.push(format!(
                    "- 自动添加：{bare}/{java_configs} 个配置的 command 是裸命令（如 `java`）。IDE 0.1.53+ 会按 PATH / JAVA_HOME 解析出实际 java，JBR 上照常加该参数；0.1.51 及更早不解析（Run 控制台会提示 `JVM options of plugin … were not added`），那种情况把 command 改成 JBR 绝对路径（`…/jbr-17.0.14/Contents/Home/bin/java`），或先手动保留该 VM option。"
                ));
            }
        } else {
            lines.push(format!(
                "- 运行配置：没有 .openidea/run.toml，或读不到；结构热更需要手动加 {ENHANCED_FLAG}"
            ));
        }

        lines.push(String::new());
        lines.push("## agent".to_string());
        lines.push(
            "agent 绑定在 executor 上：只有用 `Run / Debug '…' with Hot Reload` 启动（OpenIDEA 0.1.54+）才会注入 agent；IDE 自带的 Run / Debug 不带 agent，也不带插件声明的 VM option。"
                .to_string(),
        );
        lines.push(
            "如果 `Settings › Plugins › Hot Reload` 里没有批准 `java.agent`，executor 启动会提示授权，而不是悄悄不带 agent。"
                .to_string(),
        );
        lines.push(String::new());
        lines.push("应用控制台里应看到：".to_string());
        lines.push("```text".to_string());
        lines.push("[hotreload] INFO  Hot Reload agent 1.1.0 ...".to_string());
        lines.push("[hotreload] INFO  class/Spring engine: HotSwapAgent 2.0.5 ...".to_string());
        lines.push("[hotreload] INFO  watching 1 class directories: .../target/classes".to_string());
        lines.push("HOTSWAP AGENT: ... RELOAD ... Reloading classes [com.example.DemoService] (autoHotswap)".to_string());
        lines.push("[hotreload] INFO  reloaded mapper XML DemoMapper.xml -> com.example.mapper.DemoMapper (1 statements)".to_string());
        lines.push("```".to_string());
        lines.push(String::new());
        lines.push("## 能力边界".to_string());
        lines.push("- 方法体：任意 JDK 8–25，保存并编译后立即生效（HA Hotswapper）。".to_string());
        lines.push(
            "- Spring bean / controller / AOP：HA 2.0.5 的 Spring 插件 reprocess；新增 @GetMapping 方法也能注册。"
                .to_string(),
        );
        lines.push(format!(
            "- 增删方法/字段：JetBrains Runtime + {ENHANCED_FLAG}；标准 JDK 只允许方法体。"
        ));
        lines.push(
            "- 改父类 / 实现接口 / 删除接口：HA 和 JVM 都不支持，需要重启。".to_string(),
        );
        lines.push(
            "- Mapper XML：MyBatis / MyBatis-Plus / PageHelper 均可，保存后由构建复制到 target/classes，agent 重新解析 namespace；HA 的 MyBatis 插件已关闭。"
                .to_string(),
        );
        lines.push(
            "- Spring XML 配置（applicationContext.xml）不热更：HA 的 XML 资源监听被关闭，避免把 mapper XML 当 Spring bean 定义；Java Config / Spring Boot 不受影响。"
                .to_string(),
        );
        lines.push(
            "- 改方法签名、record 组件、注解驱动的 Bean 定义：正在执行的调用点仍指向旧结构，等同于需要重启。"
                .to_string(),
        );
        lines.push(
            "- IDE 的 javaAgents 不覆盖 Maven/Gradle goal、JUnit、Tomcat 和远程 JVM；这些配置需要自带 -javaagent。"
                .to_string(),
        );

        let report = lines.join("\n");
        let tab = ContentTab {
            key: "diagnose".to_string(),
            title: "Hot Reload 诊断".to_string(),
            file_name: Some("hot-reload-diagnose.md".to_string()),
            language: Some("markdown".to_string()),
            content: report.clone(),
            read_only: true,
            actions: Vec::new(),
        };
        if host::open_content_tab(&tab).is_err() {
            host::notify(NotificationLevel::Info, "Hot Reload 诊断", &report);
        }
        Ok(None)
    }

    fn copy_vm(&mut self) -> Result<Option<String>, Error> {
        host::copy_to_clipboard(ENHANCED_FLAG)?;
        let body = format!(
            "把 {ENHANCED_FLAG} 加到运行配置的 VM options，然后重启应用，即可增删方法/字段。\n\
             方法体热更不需要这个参数；普通 JDK 也不认它，只有 JetBrains Runtime 支持。"
        );
        if host::setting_bool("notifications").unwrap_or(true) {
            host::notify(NotificationLevel::Info, "Hot Reload · 已复制 VM options", &body);
        }
        host::log(LogLevel::Info, &body);
        Ok(None)
    }

    fn set_status(&self, text: &str, tooltip: &str) {
        let item = StatusItem {
            text: text.to_string(),
            tooltip: Some(tooltip.to_string()),
            command: Some("compile".to_string()),
        };
        if host::set_window_status_item(STATUS_ID, &item).is_err() {
            host::set_status_item(STATUS_ID, &item);
        }
    }

    fn warn(&self, title: &str, body: &str) {
        if host::setting_bool("notifications").unwrap_or(true) {
            host::notify(NotificationLevel::Warning, title, body);
        }
        host::log(LogLevel::Warn, &format!("{title}: {body}"));
    }

    fn warn_missing_commands(&mut self) {
        if self.warned_missing_permission {
            return;
        }
        self.warned_missing_permission = true;
        self.warn(
            "Hot Reload · 缺少授权",
            "自动 / 手动编译需要 `ide.commands` 权限：Settings › Plugins › Hot Reload。\
             也可以在 IDE 里手动 Build 一次，agent 同样会热更新。",
        );
    }

    fn has(&self, capability: &str) -> bool {
        self.capabilities.iter().any(|value| value == capability)
    }
}

impl RunConfig {
    /// Source file of the main class, relative to the project root.
    fn source_path(&self) -> Option<String> {
        if self.main_class.is_empty() {
            return None;
        }
        let relative = format!("src/main/java/{}.java", self.main_class.replace('.', "/"));
        Some(match self.module.trim_matches('/') {
            "" => relative,
            module => format!("{module}/{relative}"),
        })
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct RunConfig {
    id: String,
    name: String,
    module: String,
    main_class: String,
    command: String,
}

/// The saved configuration whose main class is `path`, if any.
fn config_id_for_file(ctx: &Invocation, path: &str) -> Result<Option<String>, Error> {
    let Some(project) = ctx.project() else {
        return Ok(None);
    };
    let Ok(root) = project.root() else {
        return Ok(None);
    };
    let Ok(text) = project.read_file(&format!("{root}/.openidea/run.toml")) else {
        return Ok(None);
    };
    let path = path.replace('\\', "/");
    Ok(run_configs(&text).into_iter().find_map(|config| {
        let relative = config.source_path()?;
        let candidate = format!("{root}/{relative}");
        (path == candidate.replace('\\', "/") || path.ends_with(&format!("/{relative}")))
            .then_some(config.id)
    }))
}


/// Parses the `[[configurations]]` tables of `.openidea/run.toml`, keeping
/// the ones that name a main class.
fn run_configs(text: &str) -> Vec<RunConfig> {
    let mut configs: Vec<RunConfig> = Vec::new();
    let mut current: Option<RunConfig> = None;
    for raw in text.lines() {
        let line = raw.trim();
        if line == "[[configurations]]" {
            if let Some(config) = current.take() {
                push_config(&mut configs, config);
            }
            current = Some(RunConfig::default());
            continue;
        }
        let Some(config) = current.as_mut() else {
            continue;
        };
        if let Some(value) = field(line, "id").filter(|_| config.id.is_empty()) {
            config.id = value;
        } else if let Some(value) = field(line, "name").filter(|_| config.name.is_empty()) {
            config.name = value;
        } else if line.starts_with("module") {
            if let Some(value) = field(line, "module").filter(|_| config.module.is_empty()) {
                config.module = value;
            }
        } else if let Some(value) = field(line, "mainClass") {
            config.main_class = value;
        } else if line.starts_with("command") {
            if let Some(value) = field(line, "command").filter(|_| config.command.is_empty()) {
                config.command = value;
            }
        }
    }
    if let Some(config) = current.take() {
        push_config(&mut configs, config);
    }
    configs
}

/// A command the IDE cannot read a JDK from: `java`, `sh`, no path separator.
fn is_bare_command(command: &str) -> bool {
    let command = command.trim();
    !command.is_empty() && !command.contains('/') && !command.contains('\\')
}

fn push_config(configs: &mut Vec<RunConfig>, mut config: RunConfig) {
    if config.id.is_empty() {
        return;
    }
    if config.name.is_empty() {
        config.name = config.id.rsplit(':').next().unwrap_or(&config.id).to_string();
    }
    configs.push(config);
}

/// The quoted value of `key = "…"` anywhere on a TOML line, including inside
/// an inline table such as `params = { mainClass = "…" }`.
fn field(line: &str, key: &str) -> Option<String> {
    let bytes = line.as_bytes();
    let mut from = 0;
    while let Some(offset) = line[from..].find(key) {
        let start = from + offset;
        let boundary = start == 0
            || matches!(bytes[start - 1], b' ' | b'\t' | b'{' | b',' | b'(');
        let after = &line[start + key.len()..];
        let spaced = after.starts_with([' ', '\t', '=']);
        if boundary && spaced {
            if let Some(value) = quoted_value(after) {
                return Some(value);
            }
        }
        from = start + key.len();
    }
    None
}

fn quoted_value(rest: &str) -> Option<String> {
    let rest = rest.trim_start().strip_prefix('=')?.trim_start();
    let quote = rest.chars().next()?;
    if quote != '"' && quote != '\'' {
        return None;
    }
    let body = &rest[1..];
    Some(body[..body.find(quote)?].to_string())
}

fn preferred_target(ctx: &Invocation) -> Option<String> {
    ctx.selected_paths()
        .into_iter()
        .find(|path| is_source_change(path, true))
        .or_else(|| {
            ctx.editor()
                .map(|editor| editor.path())
                .filter(|path| is_source_change(path, true))
        })
}

fn short_name(path: &str) -> &str {
    path.rsplit(['/', '\\']).next().unwrap_or(path)
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn yes(value: bool) -> &'static str {
    if value { "✓" } else { "✗" }
}

/// A saved file that should trigger a build.
///
/// Generated output is ignored (the project watcher normally excludes it too,
/// but a plugin-side filter keeps an external `mvn compile` from scheduling a
/// second build). Java is accepted anywhere inside the project; XML only where
/// mapper resources usually live, so saving an unrelated `.xml` does not build.
fn is_source_change(path: &str, include_xml: bool) -> bool {
    let normalized = path.replace('\\', "/");
    let lower = normalized.to_ascii_lowercase();
    if lower.starts_with("target/")
        || lower.starts_with("build/")
        || lower.starts_with("out/")
        || lower.starts_with(".git/")
        || lower.starts_with(".openidea/")
        || lower.contains("/target/")
        || lower.contains("/build/")
        || lower.contains("/out/")
        || lower.contains("/.git/")
        || lower.contains("/.openidea/")
        || lower.contains("/node_modules/")
        || lower.contains("/.gradle/")
    {
        return false;
    }
    if lower.ends_with(".java") {
        return true;
    }
    if include_xml && lower.ends_with(".xml") {
        return lower.contains("/src/")
            || lower.contains("/resources/")
            || lower.contains("/mapper/")
            || lower.starts_with("src/");
    }
    false
}

fn detect_java(project: &Project) -> Option<(u32, String)> {
    if let Ok(text) = project.read_file("pom.xml") {
        for key in [
            "maven.compiler.release",
            "java.version",
            "maven.compiler.source",
            "maven.compiler.target",
        ] {
            if let Some(value) = xml_tag(&text, key).and_then(|value| parse_major(&value)) {
                return Some((value, format!("pom.xml <{key}>")));
            }
        }
    }
    for file in ["build.gradle", "build.gradle.kts"] {
        if let Ok(text) = project.read_file(file) {
            if let Some(value) = gradle_major(&text) {
                return Some((value, file.to_string()));
            }
        }
    }
    None
}

fn xml_tag(xml: &str, key: &str) -> Option<String> {
    let open = format!("<{key}>");
    let close = format!("</{key}>");
    let start = xml.find(&open)? + open.len();
    let rest = &xml[start..];
    let end = rest.find(&close)?;
    Some(rest[..end].trim().to_string())
}

fn gradle_major(text: &str) -> Option<u32> {
    for key in [
        "sourceCompatibility",
        "targetCompatibility",
        "JavaLanguageVersion.of(",
        "languageVersion.set(",
    ] {
        if let Some(position) = text.find(key) {
            let rest = &text[position + key.len()..];
            let digits: String = rest
                .chars()
                .skip_while(|c| !c.is_ascii_digit())
                .take_while(|c| c.is_ascii_digit())
                .collect();
            if let Some(major) = parse_major(&digits) {
                return Some(major);
            }
        }
    }
    None
}

fn parse_major(raw: &str) -> Option<u32> {
    let value = raw.trim().trim_matches('"').trim_matches('\'').trim();
    let value = value.strip_prefix("1.").unwrap_or(value);
    let digits: String = value.chars().take_while(|c| c.is_ascii_digit()).collect();
    let major: u32 = digits.parse().ok()?;
    (1..=99).contains(&major).then_some(major)
}

register_plugin!(HotReload);

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::rc::Rc;

    use openidea_plugin::mock::MockHost;

    fn context() -> Context {
        Context {
            plugin_id: "hotreload".to_string(),
            plugin_version: "1.0.0".to_string(),
            api_version: "1.11".to_string(),
            capabilities: vec![
                "project.read".to_string(),
                "events".to_string(),
                "ide.commands".to_string(),
            ],
        }
    }

    #[test]
    fn saved_java_triggers_one_module_build() {
        let host = MockHost::install().with_capabilities(&[
            "project.read",
            "events",
            "ide.commands",
        ]);
        host.set_setting("debounceMs", "0");
        let builds = Rc::new(RefCell::new(Vec::<Option<String>>::new()));
        let recorded = builds.clone();
        host.on_command("build.buildModule", move |arg| {
            recorded.borrow_mut().push(arg.map(str::to_owned));
            Ok(None)
        });

        let mut plugin = HotReload::default();
        plugin.activate(&context()).unwrap();
        plugin.handle_events(
            &[Event::FilesChanged(FilesChanged {
                modified: vec!["/project/src/main/java/com/acme/A.java".to_string()],
                ..FilesChanged::default()
            })],
            &host.invocation(),
        );

        assert_eq!(
            builds.borrow().as_slice(),
            &[Some("/project/src/main/java/com/acme/A.java".to_string())]
        );
    }

    #[test]
    fn mapper_xml_triggers_a_build() {
        let host = MockHost::install().with_capabilities(&[
            "project.read",
            "events",
            "ide.commands",
        ]);
        host.set_setting("debounceMs", "0");
        let builds = Rc::new(RefCell::new(Vec::<Option<String>>::new()));
        let recorded = builds.clone();
        host.on_command("build.buildModule", move |arg| {
            recorded.borrow_mut().push(arg.map(str::to_owned));
            Ok(None)
        });

        let mut plugin = HotReload::default();
        plugin.activate(&context()).unwrap();
        plugin.handle_events(
            &[Event::FilesChanged(FilesChanged {
                created: vec!["/project/src/main/resources/mapper/A.xml".to_string()],
                ..FilesChanged::default()
            })],
            &host.invocation(),
        );

        assert_eq!(builds.borrow().len(), 1);
    }

    #[test]
    fn ignored_changes_do_not_build() {
        let host = MockHost::install().with_capabilities(&[
            "project.read",
            "events",
            "ide.commands",
        ]);
        host.set_setting("debounceMs", "0");
        let builds = Rc::new(RefCell::new(0usize));
        let recorded = builds.clone();
        host.on_command("build.buildModule", move |_| {
            *recorded.borrow_mut() += 1;
            Ok(None)
        });

        let mut plugin = HotReload::default();
        plugin.activate(&context()).unwrap();
        plugin.handle_events(
            &[Event::FilesChanged(FilesChanged {
                modified: vec![
                    "/project/target/classes/com/acme/A.class".to_string(),
                    "/project/README.md".to_string(),
                    "/project/pom.xml".to_string(),
                ],
                ..FilesChanged::default()
            })],
            &host.invocation(),
        );

        assert_eq!(*builds.borrow(), 0);
    }

    #[test]
    fn ignores_generated_output() {
        assert!(!is_source_change(
            "/p/target/classes/com/a/B.java",
            true
        ));
        assert!(!is_source_change(
            "/p/build/classes/java/main/com/a/B.java",
            true
        ));
        assert!(!is_source_change(
            "/p/.openidea/run.toml",
            true
        ));
        assert!(!is_source_change("target/classes/com/acme/A.java", true));
        assert!(!is_source_change(".openidea/run.toml", true));
    }

    #[test]
    fn accepts_sources_and_mapper_xml() {
        assert!(is_source_change(
            "/p/src/main/java/com/a/B.java",
            true
        ));
        assert!(is_source_change(
            "/p/src/main/resources/mapper/B.xml",
            true
        ));
        assert!(is_source_change("/p/mapper/B.xml", true));
        assert!(!is_source_change(
            "/p/design/ui.xml",
            true
        ));
        assert!(!is_source_change(
            "/p/src/main/resources/mapper/B.xml",
            false
        ));
    }

    #[test]
    fn parses_java_versions() {
        assert_eq!(parse_major("17"), Some(17));
        assert_eq!(parse_major("1.8"), Some(8));
        assert_eq!(parse_major("\"21\""), Some(21));
        assert_eq!(parse_major("nope"), None);
    }

    #[test]
    fn reads_pom_properties() {
        let pom = r#"<properties>
            <java.version>17</java.version>
            <maven.compiler.source>21</maven.compiler.source>
        </properties>"#;
        assert_eq!(xml_tag(pom, "maven.compiler.source").as_deref(), Some("21"));
        assert_eq!(xml_tag(pom, "java.version").as_deref(), Some("17"));
    }

    #[test]
    fn reads_gradle_toolchain() {
        assert_eq!(
            gradle_major("jvmToolchain { languageVersion.set(JavaLanguageVersion.of(21)) }"),
            Some(21)
        );
        assert_eq!(
            gradle_major("sourceCompatibility = JavaVersion.VERSION_11"),
            Some(11)
        );
    }

    const RUN_TOML: &str = "\
[[configurations]]
id = \"application:app:com.acme.App\"
name = \"App\"
kind = \"spring-boot\"
command = \"java\"
module = \"app\"
params = { classpath = \"${classpath}\", mainClass = \"com.acme.App\", module = \"app\", programArgs = \"\" }

[[configurations]]
id = \"application:shell:deploy\"
name = \"Deploy\"
kind = \"shell\"
command = \"sh\"
";

    #[test]
    fn parses_run_configurations_without_native_toml() {
        let configs = run_configs(RUN_TOML);
        assert_eq!(configs.len(), 2);
        assert_eq!(configs[0].id, "application:app:com.acme.App");
        assert_eq!(configs[0].name, "App");
        assert_eq!(configs[0].module, "app");
        assert_eq!(configs[0].command, "java");
        // A shell configuration has no main class; only Java ones count.
        assert_eq!(configs[1].id, "application:shell:deploy");
        assert!(configs[1].main_class.is_empty());
    }

    const SERVICE_ARG: &str = r#"{"services":[{"provider":"runConfigurations","id":"application:app:com.acme.App","kind":"spring-boot","name":"App","running":false,"runConfigurationId":"application:app:com.acme.App"}]}"#;

    fn launch_host() -> MockHost {
        let host = MockHost::install().with_capabilities(&["project.read", "events", "ide.commands"]);
        host.set_project_root(Some("/project"));
        host.add_file("/project/.openidea/run.toml", RUN_TOML);
        host.add_file("/project/app/src/main/java/com/acme/App.java", "package com.acme; class App {}");
        host
    }

    #[test]
    fn services_run_forwards_the_services_argument_to_the_executor() {
        let host = launch_host();
        let calls = Rc::new(RefCell::new(Vec::<Option<String>>::new()));
        let recorded = calls.clone();
        host.on_command("pluginExecutor.weigram.hotreload.run", move |arg| {
            recorded.borrow_mut().push(arg.map(str::to_owned));
            Ok(None)
        });

        let mut plugin = HotReload::default();
        plugin.activate(&context()).unwrap();
        plugin
            .run_command("run-service", &host.invocation(), Some(SERVICE_ARG))
            .unwrap();

        assert_eq!(calls.borrow().as_slice(), &[Some(SERVICE_ARG.to_string())]);
    }

    #[test]
    fn services_debug_uses_the_debug_executor() {
        let host = launch_host();
        let calls = Rc::new(RefCell::new(Vec::<String>::new()));
        let recorded = calls.clone();
        host.on_command("pluginExecutor.weigram.hotreload.debug", move |_| {
            recorded.borrow_mut().push("debug".to_string());
            Ok(None)
        });

        let mut plugin = HotReload::default();
        plugin.activate(&context()).unwrap();
        plugin
            .run_command("debug-service", &host.invocation(), Some(SERVICE_ARG))
            .unwrap();

        assert_eq!(calls.borrow().as_slice(), ["debug"]);
    }

    #[test]
    fn editor_run_starts_the_executor_for_the_file_configuration() {
        let host = launch_host();
        let calls = Rc::new(RefCell::new(Vec::<Option<String>>::new()));
        let recorded = calls.clone();
        host.on_command("pluginExecutor.weigram.hotreload.run", move |arg| {
            recorded.borrow_mut().push(arg.map(str::to_owned));
            Ok(None)
        });
        host.open_editor("/project/app/src/main/java/com/acme/App.java", "java", "class App {}", &[]);

        let mut plugin = HotReload::default();
        plugin.activate(&context()).unwrap();
        plugin
            .run_command("run-context", &host.invocation(), None)
            .unwrap();

        assert_eq!(
            calls.borrow().as_slice(),
            &[Some("application:app:com.acme.App".to_string())]
        );
    }

    #[test]
    fn short_names() {
        assert_eq!(short_name("/a/b/C.java"), "C.java");
        assert_eq!(short_name("C.java"), "C.java");
    }
}
