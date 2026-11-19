#!/usr/bin/env python3
"""Build the reproducible hot reload agent jar.

The IDE records the agent jar's content hash when the user grants the
``java.agent`` capability and refuses a jar whose hash changed without a new
approval. Everything here is therefore deterministic: fixed timestamps,
sorted entries, no directory entries, and a manifest generated from scratch.

The jar contains three parts:

* our agent (class directory),
* ASM (for the MyBatis Configuration transformer),
* HotSwapAgent 2.0.5 (class and Spring plugin engine).

Usage: package.py <classes-dir> <asm-jar> <hotswap-agent-jar> <output-jar> [version]
"""

import os
import sys
import zipfile

MANIFEST = """Manifest-Version: 1.0\r
Premain-Class: com.hotreload.agent.HotReloadPremain\r
Agent-Class: com.hotreload.agent.HotReloadPremain\r
Can-Redefine-Classes: true\r
Can-Retransform-Classes: true\r
Can-Set-Native-Method-Prefix: true\r
Implementation-Title: OpenIDEA Hot Reload Agent\r
Implementation-Version: {version}\r
Created-By: OpenIDEA Hot Reload\r
\r
"""

# Properties read by HotSwapAgent for the agent class loader. The same values
# are passed as premain arguments; keeping both makes the behavior independent
# of an application's own hotswap-agent.properties.
HOTSWAP_PROPERTIES = """# OpenIDEA Hot Reload agent: HotSwapAgent 2.0.5 defaults.
# autoHotswap watches the application classpath and reloads changed classes,
# which is what lets the Spring plugin reprocess beans and controllers.
autoHotswap=true
# The XML reloader of this agent owns MyBatis mapper XML; HA's own MyBatis
# plugins would parse the same file a second time and can conflict with
# MyBatis-Plus / Flowable proxies.
disabledPlugins=MyBatis,MyBatisPlus
LOGGER=info
"""

FIXED_TIME = (2026, 1, 1, 0, 0, 0)

# Not copied from HotSwapAgent: we regenerate the manifest, replace its
# default properties with the one above, and drop archive signatures (invalid
# after rewriting the jar) and module-info (the agent is a class-path jar).
SKIP_PREFIXES = ("META-INF/",)
SKIP_NAMES = {
    "META-INF/MANIFEST.MF",
    "META-INF/INDEX.LIST",
    "module-info.class",
    "hotswap-agent.properties",
}


def agent_entries(classes_dir):
    for root, _dirs, files in os.walk(classes_dir):
        for name in sorted(files):
            if not name.endswith(".class"):
                continue
            path = os.path.join(root, name)
            archive = os.path.relpath(path, classes_dir).replace(os.sep, "/")
            with open(path, "rb") as handle:
                yield archive, handle.read()


def jar_entries(jar, prefixes):
    with zipfile.ZipFile(jar) as source:
        for item in sorted(source.infolist(), key=lambda entry: entry.filename):
            name = item.filename
            if item.is_dir() or name in SKIP_NAMES:
                continue
            if name.startswith("META-INF/") and (
                name.endswith(".SF") or name.endswith(".RSA") or name.endswith(".DSA")
            ):
                continue
            if "module-info.class" in name:
                continue
            if prefixes and not any(name.startswith(prefix) for prefix in prefixes):
                continue
            yield name, source.read(name)


def main():
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    classes, asm_jar, hotswap_jar, output = sys.argv[1:5]
    version = sys.argv[5] if len(sys.argv) > 5 else "1.1.0"

    merged = {}
    # HotSwapAgent first, then our classes and ASM override a same-named entry.
    for name, data in jar_entries(hotswap_jar, prefixes=()):
        merged.setdefault(name, data)
    for name, data in jar_entries(asm_jar, prefixes=("org/objectweb/asm/",)):
        merged[name] = data
    for name, data in agent_entries(classes):
        merged[name] = data

    temp = output + ".tmp"
    with zipfile.ZipFile(temp, "w", zipfile.ZIP_DEFLATED) as archive:
        manifest = zipfile.ZipInfo("META-INF/MANIFEST.MF", date_time=FIXED_TIME)
        manifest.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(manifest, MANIFEST.format(version=version).encode("utf-8"))

        properties = zipfile.ZipInfo("hotswap-agent.properties", date_time=FIXED_TIME)
        properties.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(properties, HOTSWAP_PROPERTIES.encode("utf-8"))

        for name in sorted(merged):
            info = zipfile.ZipInfo(name, date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, merged[name])
    os.replace(temp, output)

    with zipfile.ZipFile(output) as archive:
        names = set(archive.namelist())
        assert "com/hotreload/agent/HotReloadPremain.class" in names
        assert "com/hotreload/agent/MyBatisConfigTransformer.class" in names
        assert "org/objectweb/asm/ClassReader.class" in names
        assert "org/hotswap/agent/HotswapAgent.class" in names
        assert "org/hotswap/agent/plugin/spring/SpringPlugin.class" in names
        assert "hotswap-agent.properties" in names
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        assert "Premain-Class: com.hotreload.agent.HotReloadPremain" in manifest
    print(
        "packaged %s (%d entries, %d bytes)"
        % (output, len(merged) + 2, os.path.getsize(output))
    )


if __name__ == "__main__":
    main()
