# ASM

`asm-9.8.jar` is [ASM 9.8](https://asm.ow2.io/) (BSD-3-Clause), used by the
agent's class-file transformer to inject one call into
`Configuration.addMappedStatement`. It is bundled into
`agent/hotreload-agent.jar` so the agent is a single self-contained jar.

The jar is unmodified; the upstream license is reproduced here:

```text
Copyright (c) 2000-2011 INRIA, France Telecom
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holders nor the names of its
   contributors may be used to endorse or promote products derived from this
   software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```


# HotSwapAgent

`hotswap-agent-2.0.5.jar` is [HotSwapAgent 2.0.5](https://github.com/HotswapProjects/HotswapAgent)
(GPL-2.0), used as the class-redefinition and Spring bean/controller/AOP engine.
The jar is unmodified. It is merged into `agent/hotreload-agent.jar` at build
time, with its manifest replaced and its default `hotswap-agent.properties`
replaced by the one generated in `agent/package.py`.

At runtime the agent disables the HA `MyBatis`, `MyBatis-Plus` and
`WatchResources` plugins (our XML reloader owns mapper XML) and replaces
`org.hotswap.agent.plugin.spring.SpringPlugin.registerResourceListeners` with
an empty method so mapper XML is not parsed as a Spring bean-definition file.
The upstream GPL-2.0 license is the same license as this plugin; the full text
is in the repository root `LICENSE`.
