## 0.6.1 (2025-05-14)

### 🚀 Features

- jira count tool and lazy laod the jiraFieldMappingDescription ([#9](https://github.com/biznet-io/coday/pull/9))

### 🩹 Fixes

- do not write silently the default coday.yaml file ([#73](https://github.com/biznet-io/coday/pull/73))

### ❤️ Thank You

- c-monot-whoz
- vincent-audibert-whoz

## 0.6.0 (2025-04-28)

### 🚀 Features

- add copy button for agent responses in web UI ([#60](https://github.com/biznet-io/coday/pull/60), [#62](https://github.com/biznet-io/coday/pull/62))
- #49 Support MCP Inspector debug mode as separate process (not as tool server) ([#55](https://github.com/biznet-io/coday/pull/55), [#49](https://github.com/biznet-io/coday/issues/49))

### 🩹 Fixes

- textarea component default value not lost to history functionality ([#56](https://github.com/biznet-io/coday/pull/56))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.5.1 (2025-04-08)

### 🩹 Fixes

- integration config lost when editing MCP config ([#39](https://github.com/biznet-io/coday/pull/39))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.5.0 (2025-04-08)

### 🚀 Features

- add web UI options ([#31](https://github.com/biznet-io/coday/pull/31))
- add MCP first integration ([#29](https://github.com/biznet-io/coday/pull/29))

### 🩹 Fixes

- tweak the pdf-parse import as lib not ESM ([#37](https://github.com/biznet-io/coday/pull/37))

### ❤️ Thank You

- dpalita-whoz @dpalita-whoz
- vincent-audibert-whoz

## 0.4.0 (2025-03-25)

### 🚀 Features

- 27 allow to inject agent definitions from outside the project

### 🩹 Fixes

- JIRA tools init is cached
- JIRA tools init is cached
- Fix release
- improve tool creation with error logs

### ❤️ Thank You

- Vincent Palita
- vincent-audibert-whoz
- vincent.audibert

## 0.3.4 (2025-03-20)

### 🩹 Fixes

- JIRA tools init is cached
- JIRA tools init is cached
- Fix release

### ❤️ Thank You

- Vincent Palita
- vincent-audibert-whoz
- vincent.audibert

## 0.3.3 (2025-03-20)

### 🩹 Fixes

- Release app in GitHub Actions workflow ([ddeb62b](https://github.com/biznet-io/coday/commit/ddeb62b))

### ❤️ Thank You

- Vincent Palita

## 0.3.2 (2025-03-20)

### 🩹 Fixes

- update the last connected date on user message + 8h inactivity allowed ([83aafb1](https://github.com/biznet-io/coday/commit/83aafb1))
- update the last connected date ([#19](https://github.com/biznet-io/coday/pull/19))
- JIRA tools selectables as other tools ([8707143](https://github.com/biznet-io/coday/commit/8707143))
- JIRA tools selectables as other tools ([#20](https://github.com/biznet-io/coday/pull/20))

### ❤️ Thank You

- vincent-audibert-whoz
- vincent.audibert

## 0.3.1 (2025-03-19)

### 🚀 Features

- add default agent per user and project ([546ab6e](https://github.com/biznet-io/coday/commit/546ab6e))

### 🩹 Fixes

- remove un-necessary error log on optional `/agents` folders ([ae95c6e](https://github.com/biznet-io/coday/commit/ae95c6e))
- add logs in ai.client.ts and implementations. ([9df6783](https://github.com/biznet-io/coday/commit/9df6783))
- better error logs ([b829418](https://github.com/biznet-io/coday/commit/b829418))
- improve logs ([820484f](https://github.com/biznet-io/coday/commit/820484f))
- add "-f" option in project.json to allow removal of not found package.json file ([0260f91](https://github.com/biznet-io/coday/commit/0260f91))

### ❤️ Thank You

- Vincent Palita
- vincent-audibert-whoz
- vincent.audibert

## 0.3.0 (2025-03-18)

### 🚀 Features

- add debug capabilities ([dbc5b53](https://github.com/biznet-io/coday/commit/dbc5b53))

### ❤️ Thank You

- Vincent Palita

## 0.2.0 (2025-03-11)

### 🚀 Features

- Add AiThread forking mechanism with agent delegation support ([2d072d3](https://github.com/biznet-io/coday/commit/2d072d3))
- allow AiThreads to be forked and price-merged. ([e37c1f2](https://github.com/biznet-io/coday/commit/e37c1f2))
- open delegate tool ([5223bbb](https://github.com/biznet-io/coday/commit/5223bbb))
- add Project Manager agent ([8698ae5](https://github.com/biznet-io/coday/commit/8698ae5))
- add agent docs ([3778984](https://github.com/biznet-io/coday/commit/3778984))
- move to sonnet 3.7, not thinking yet ([56c72de](https://github.com/biznet-io/coday/commit/56c72de))
- init context simplification ([7a02fb1](https://github.com/biznet-io/coday/commit/7a02fb1))
- move delegate tool declaration ([6b6f310](https://github.com/biznet-io/coday/commit/6b6f310))
- fix delegation attempt, to test... ([c1f7774](https://github.com/biznet-io/coday/commit/c1f7774))
- formatting... ([8b8bfa6](https://github.com/biznet-io/coday/commit/8b8bfa6))
- multi-agent foundations ([10afdf0](https://github.com/biznet-io/coday/commit/10afdf0))
- add `--local` option to take the repo clone folder name as the project name ([87a6c1d](https://github.com/biznet-io/coday/commit/87a6c1d))
- add `--local` option to auto-select the project ([1471658](https://github.com/biznet-io/coday/commit/1471658))
- allow agents to be defined outside of `coday.yaml` ([0f3d494](https://github.com/biznet-io/coday/commit/0f3d494))
- allow agents to be defined outside of `coday.yaml` ([1e82fe7](https://github.com/biznet-io/coday/commit/1e82fe7))

### 🩹 Fixes

- manage aithread runStatus for delegation ([db67157](https://github.com/biznet-io/coday/commit/db67157))

### ❤️ Thank You

- vincent-audibert-whoz
- vincent.audibert

## 0.1.0 (2025-03-03)

### 🚀 Features

- bump dependencies & upgrade claude-sonnet to 3.7 ([72dd074](https://github.com/biznet-io/coday/commit/72dd074))
- bump repository to 0.0.7 ([60a21e1](https://github.com/biznet-io/coday/commit/60a21e1))
- enable jira global research ([#2](https://github.com/biznet-io/coday/pull/2))

### ❤️ Thank You

- c-monot-whoz
- Vincent Palita

## 0.0.1-7 (2025-02-28)

### 🚀 Features

- bump repository to 0.0.7 ([60a21e1](https://github.com/biznet-io/coday/commit/60a21e1))

### ❤️ Thank You

- Vincent Palita

## 0.0.1-6 (2025-02-28)

### 🚀 Features

- bump dependencies & upgrade claude-sonnet to 3.7 ([72dd074](https://github.com/biznet-io/coday/commit/72dd074))

### ❤️ Thank You

- Vincent Palita

## 0.0.1-5 (2025-02-04)

### 🚀 Features

- add capability to use a localLlm
- enable async tools to provide fetched data when we build the tool, implement a search-jira-ticket function to enable global research on jira
- reduce the number of tickets fetched
- select tools per agent depending on their declared integrations setting
- **jira:** enable add comment on ticket

### 🩹 Fixes

- handle default integrations on agent definitions
- better display of help
- **nx:** remove no_auth from web serve target
- **terminal:** make it work with cjs

### ❤️ Thank You

- Charles Monot
- Vincent Audibert
- Vincent Palita
- vincent.audibert

## 0.0.1-4 (2024-12-18)

### 🚀 Features

- **root:** add nx release

### ❤️ Thank You

- Vincent Palita

## 0.0.1-3 (2024-12-16)

This was a version bump only, there were no code changes.

## 0.0.1-2 (2024-12-16)

This was a version bump only, there were no code changes.

## 0.0.1-1 (2024-12-16)

This was a version bump only, there were no code changes.

## 0.0.1-0 (2024-12-16)

### 🚀 Features

- **coday.yaml:** add raw agents for testing
- **log:** log in frontend and backend to better diagnose the spurious disconnection and loss of context
- **thread:** add autosave

### ❤️ Thank You

- vincent.audibert

# 1.2.0 (2024-12-12)

This was a version bump only, there were no code changes.

# 1.0.0 (2024-12-12)

### 🚀 Features

- add flexible parameter handling to git log and git show functions
- `thread save` can take a title and save it without interaction
- display selected project in user prompt and do not select automatically previous project
- add script to open files in intellij
- expose a tool for Coday to ask questions to the user
- rework Coday systemInstruction for more leeway and flexibility.
- add a remove file tool (through node unlink)
- eventify interactor and tools
- add memory service and handler (useless for now)
- add memory.tools.ts to add a memory
- add memories in initial context
- connect memory-service to config.service
- split current coday config into project folders (still in ./coday)
- split current coday config into project folders (still in ./coday)
- add file-map and coday-prompt-chains
- add load-file-handler
- add load parent handler for file and folder handlers
- add confluence basic integration to search for pages by text and retrieve page by id
- enhance web ui significantly
- refine scrolling and wrapping
- handle multiple clients on one server and show selected project.
- add timestamp to log clients connects and disconnects
- add thinking event and display to show the LLM is doing something...long
- add draft gemini client
- make subTask available by default
- handle assistant by name search depending on aiClient.multiAssistant flag
- add delegate handler, tool and use in small-task prompt chain
- stricter write by chunk, with explicit status return
- add an 'iterate' handler
- focus on textarea or choice when they appear.
- memorization standard, not anymore an integration
- enable thread conversation with claude and with tools.
- start preparing move to stateless clients.
- create ToolSet class for modular tool management
- move ai setup through integration to its own config part
- move again ai setup to user config (new)
- remove dependencies on AI meta-integration
- clean up last references to ai integrations
- add handler to curate memories
- prepare ai-thread.ts
- use AiThread for Claude
- silent implementation of changes in ai.client.ts
- silent management and selection of aiThreads
- using aiThread and fixing bugs
- add stop at various levels
- adding tests on file ai thread repository
- simpler save behavior
- simpler find aiThread behavior
- add stop endpoint to server.ts
- ai-thread handler
- add stop in ui
- show thread messages on reload
- openai on aithreads !!!! YEEEHAAAA!!!!
- select ai-threads at startup
- clean up ai.client.ts and implementations
- remove or de-activate handlers made irrelevant by recent aiThread impacts on aiclients.
- clean and pricing runs
- self-review
- propose new thread as first selection from empty state
- ask user when consumption threshold of the run are reached
- Re-enable OpenAI Assistants with improved agent architecture
- **file-tools:** Add comprehensive documentation and read-only mode support
- **gitlab:** add list issue and list merge requests
- **openai-client:** Enhance usage tracking and message processing

### 🩹 Fixes

- recognize and handle assistant selection only commands
- assistants could not call each other
- inconsistency in confluence vs jira integration apiUrl expectation
- attenuate the bad gemini performance with more output tokens.

### ❤️ Thank You

- Vincent Audibert
- vincent.audibert
