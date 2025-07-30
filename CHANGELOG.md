







## 0.15.0 (2025-07-29)

### 🚀 Features

- issue 61 add images ([#141](https://github.com/whoz-oss/coday/pull/141))

### 🩹 Fixes

- insufficient write-file-chunk input validation ([#138](https://github.com/whoz-oss/coday/pull/138))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.14.0 (2025-07-01)

### 🩹 Fixes

- incorrect toolResponse creation resulting in lost link to toolRequest ([ae43868](https://github.com/whoz-oss/coday/commit/ae43868))
- incorrect toolResponse creation ([#134](https://github.com/whoz-oss/coday/pull/134))
- compact more than just the strict over-budget ([fbe55bd](https://github.com/whoz-oss/coday/commit/fbe55bd))
- do not overshoot the compacting limits ([0cf188c](https://github.com/whoz-oss/coday/commit/0cf188c))
- too frequent compaction ([#135](https://github.com/whoz-oss/coday/pull/135))

### ❤️ Thank You

- vincent-audibert-whoz
- vincent.audibert



## 0.13.1 (2025-06-25)

### 🚀 Features

- self-review thread compaction ([617ee21](https://github.com/whoz-oss/coday/commit/617ee21))
- various misc fixes or changes ([7939617](https://github.com/whoz-oss/coday/commit/7939617))
- move MCP errors in debug ([e7447ee](https://github.com/whoz-oss/coday/commit/e7447ee))
- reformat queryUser tools description ([cd05bdf](https://github.com/whoz-oss/coday/commit/cd05bdf))

### 🩹 Fixes

- avoid contextWindow overflow by compaction (or last-resort truncation), WIP... ([108fe0f](https://github.com/whoz-oss/coday/commit/108fe0f))
- extract partition function ([71239b9](https://github.com/whoz-oss/coday/commit/71239b9))
- englished comments ([ec272d5](https://github.com/whoz-oss/coday/commit/ec272d5))
- remove redundant tools displayText ([317078b](https://github.com/whoz-oss/coday/commit/317078b))
- remove more redundant displayText ([4aa04b8](https://github.com/whoz-oss/coday/commit/4aa04b8))
- lower chars per token to avoid contextWindow overflow ([e13544c](https://github.com/whoz-oss/coday/commit/e13544c))
- thread truncating for context window ([#129](https://github.com/whoz-oss/coday/pull/129))

### ❤️ Thank You

- vincent-audibert-whoz
- vincent.audibert

## 0.13.0 (2025-06-17)

### 🚀 Features

- Add voice synthesis for agent responses ([#121](https://github.com/whoz-oss/coday/pull/121))
- stats handler ([#126](https://github.com/whoz-oss/coday/pull/126))

### 🩹 Fixes

- stop mcps with thread ([#119](https://github.com/whoz-oss/coday/pull/119))
- silent error on mandatory docs ([#125](https://github.com/whoz-oss/coday/pull/125))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.12.0 (2025-06-16)

### 🚀 Features

- ai providers in coday yaml and free config ([#74](https://github.com/whoz-oss/coday/pull/74))
- #110 implement intelligent rate limiting and throttling for Anthropic API ([#116](https://github.com/whoz-oss/coday/pull/116), [#110](https://github.com/whoz-oss/coday/issues/110))
- #117 optimize Anthropic cache with mobile marker strategy ([#118](https://github.com/whoz-oss/coday/pull/118), [#117](https://github.com/whoz-oss/coday/issues/117))
- separate token info from agent and price ([#120](https://github.com/whoz-oss/coday/pull/120))

### ❤️ Thank You

- vincent-audibert-whoz



## 0.11.0 (2025-06-03)

### 🚀 Features

- add user bio feature with user/project levels for personalized agent context ([#104](https://github.com/whoz-oss/coday/pull/104))
- add transient date/time injection for agent context ([#105](https://github.com/whoz-oss/coday/pull/105))
- #106 add usage logging with daily file granularity ([#108](https://github.com/whoz-oss/coday/pull/108), [#106](https://github.com/whoz-oss/coday/issues/106))

### 🩹 Fixes

- agent selection with multiline input ([#107](https://github.com/whoz-oss/coday/pull/107))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.10.0 (2025-05-23)

### 🚀 Features

- speech-to-text integration for web interface ([#102](https://github.com/whoz-oss/coday/pull/102))
- clean tool logs ([#100](https://github.com/whoz-oss/coday/pull/100))
- expand memory handlers ([#86](https://github.com/whoz-oss/coday/pull/86))
- #58 more generic read file tool ([#101](https://github.com/whoz-oss/coday/pull/101))
- various ux,copy user message, less scroll, logo ([#96](https://github.com/whoz-oss/coday/pull/96))
- bump gemini & sonnet versions ([#97](https://github.com/whoz-oss/coday/pull/97))

### 🩹 Fixes

- put lint back ([#98](https://github.com/whoz-oss/coday/pull/98))

### ❤️ Thank You

- Vincent Palita @vincentpalita-whoz
- vincent-audibert-whoz

## 0.8.0 (2025-05-22)

### 🚀 Features

- #43 create jira ticket ([#70](https://github.com/whoz-oss/coday/pull/70), [#43](https://github.com/whoz-oss/coday/issues/43))

### ❤️ Thank You

- Vincent Palita @vincentpalita-whoz

## 0.7.2 (2025-05-21)

### 🩹 Fixes

- clean agent selection, get rid of dubious and untested logic ([#93](https://github.com/whoz-oss/coday/pull/93))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.7.1 (2025-05-20)

### 🚀 Features

- update dependencies

## 0.7.0 (2025-05-20)

### 🚀 Features

- Display tool request and response events in UI ([#85](https://github.com/whoz-oss/coday/pull/85))

### 🩹 Fixes

- issue 80, agent selection again ([#82](https://github.com/whoz-oss/coday/pull/82))

### ❤️ Thank You

- Vincent Palita @vincentpalita-whoz

## 0.6.1 (2025-05-20)

### 🚀 Features

- issue 65 ai tools splitting 2 ([#83](https://github.com/whoz-oss/coday/pull/83))
- #87 update nx to 21.0.3 ([#88](https://github.com/whoz-oss/coday/pull/88), [#87](https://github.com/whoz-oss/coday/issues/87))

### 🩹 Fixes

- remove too early default case in agent selection ([#81](https://github.com/whoz-oss/coday/pull/81))
- agent selection from thread ([#78](https://github.com/whoz-oss/coday/pull/78))

### ❤️ Thank You

- c-monot-whoz
- vincent-audibert-whoz

## 0.6.0 (2025-04-28)

### 🚀 Features

- jira count tool and lazy laod the jiraFieldMappingDescription ([#9](https://github.com/whoz-oss/coday/pull/9))
- add copy button for agent responses in web UI ([#60](https://github.com/whoz-oss/coday/pull/60), [#62](https://github.com/whoz-oss/coday/pull/62))
- #49 Support MCP Inspector debug mode as separate process (not as tool server) ([#55](https://github.com/whoz-oss/coday/pull/55), [#49](https://github.com/whoz-oss/coday/issues/49))

### 🩹 Fixes

- do not write silently the default coday.yaml file ([#73](https://github.com/whoz-oss/coday/pull/73))
- textarea component default value not lost to history functionality ([#56](https://github.com/whoz-oss/coday/pull/56))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.5.1 (2025-04-08)

### 🩹 Fixes

- integration config lost when editing MCP config ([#39](https://github.com/whoz-oss/coday/pull/39))

### ❤️ Thank You

- vincent-audibert-whoz

## 0.5.0 (2025-04-08)

### 🚀 Features

- add web UI options ([#31](https://github.com/whoz-oss/coday/pull/31))
- add MCP first integration ([#29](https://github.com/whoz-oss/coday/pull/29))

### 🩹 Fixes

- tweak the pdf-parse import as lib not ESM ([#37](https://github.com/whoz-oss/coday/pull/37))

### ❤️ Thank You

- dpalita-whoz @dpalita-whoz
- vincent-audibert-whoz

## 0.4.0 (2025-03-25)

### 🚀 Features

- 27 allow to inject agent definitions from outside the project

### 🩹 Fixes

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
- Fix release

### ❤️ Thank You

- Vincent Palita
- vincent-audibert-whoz
- vincent.audibert

## 0.3.3 (2025-03-20)

### 🩹 Fixes

- Release app in GitHub Actions workflow ([ddeb62b](https://github.com/whoz-oss/coday/commit/ddeb62b))

### ❤️ Thank You

- Vincent Palita

## 0.3.2 (2025-03-20)

### 🩹 Fixes

- update the last connected date on user message + 8h inactivity allowed ([83aafb1](https://github.com/whoz-oss/coday/commit/83aafb1))
- update the last connected date ([#19](https://github.com/whoz-oss/coday/pull/19))
- JIRA tools selectables as other tools ([8707143](https://github.com/whoz-oss/coday/commit/8707143))
- JIRA tools selectables as other tools ([#20](https://github.com/whoz-oss/coday/pull/20))

### ❤️ Thank You

- vincent-audibert-whoz
- vincent.audibert

## 0.3.1 (2025-03-19)

### 🚀 Features

- add default agent per user and project ([546ab6e](https://github.com/whoz-oss/coday/commit/546ab6e))

### 🩹 Fixes

- remove un-necessary error log on optional `/agents` folders ([ae95c6e](https://github.com/whoz-oss/coday/commit/ae95c6e))
- add logs in ai.client.ts and implementations. ([9df6783](https://github.com/whoz-oss/coday/commit/9df6783))
- better error logs ([b829418](https://github.com/whoz-oss/coday/commit/b829418))
- improve logs ([820484f](https://github.com/whoz-oss/coday/commit/820484f))
- add "-f" option in project.json to allow removal of not found package.json file ([0260f91](https://github.com/whoz-oss/coday/commit/0260f91))

### ❤️ Thank You

- Vincent Palita
- vincent-audibert-whoz
- vincent.audibert

## 0.3.0 (2025-03-18)

### 🚀 Features

- add debug capabilities ([dbc5b53](https://github.com/whoz-oss/coday/commit/dbc5b53))

### ❤️ Thank You

- Vincent Palita

## 0.2.0 (2025-03-11)

### 🚀 Features

- Add AiThread forking mechanism with agent delegation support ([2d072d3](https://github.com/whoz-oss/coday/commit/2d072d3))
- allow AiThreads to be forked and price-merged. ([e37c1f2](https://github.com/whoz-oss/coday/commit/e37c1f2))
- open delegate tool ([5223bbb](https://github.com/whoz-oss/coday/commit/5223bbb))
- add Project Manager agent ([8698ae5](https://github.com/whoz-oss/coday/commit/8698ae5))
- add agent docs ([3778984](https://github.com/whoz-oss/coday/commit/3778984))
- move to sonnet 3.7, not thinking yet ([56c72de](https://github.com/whoz-oss/coday/commit/56c72de))
- init context simplification ([7a02fb1](https://github.com/whoz-oss/coday/commit/7a02fb1))
- move delegate tool declaration ([6b6f310](https://github.com/whoz-oss/coday/commit/6b6f310))
- fix delegation attempt, to test... ([c1f7774](https://github.com/whoz-oss/coday/commit/c1f7774))
- formatting... ([8b8bfa6](https://github.com/whoz-oss/coday/commit/8b8bfa6))
- multi-agent foundations ([10afdf0](https://github.com/whoz-oss/coday/commit/10afdf0))
- add `--local` option to take the repo clone folder name as the project name ([87a6c1d](https://github.com/whoz-oss/coday/commit/87a6c1d))
- add `--local` option to auto-select the project ([1471658](https://github.com/whoz-oss/coday/commit/1471658))
- allow agents to be defined outside of `coday.yaml` ([0f3d494](https://github.com/whoz-oss/coday/commit/0f3d494))
- allow agents to be defined outside of `coday.yaml` ([1e82fe7](https://github.com/whoz-oss/coday/commit/1e82fe7))

### 🩹 Fixes

- manage aithread runStatus for delegation ([db67157](https://github.com/whoz-oss/coday/commit/db67157))

### ❤️ Thank You

- vincent-audibert-whoz
- vincent.audibert

## 0.1.0 (2025-03-03)

### 🚀 Features

- bump dependencies & upgrade claude-sonnet to 3.7 ([72dd074](https://github.com/whoz-oss/coday/commit/72dd074))
- bump repository to 0.0.7 ([60a21e1](https://github.com/whoz-oss/coday/commit/60a21e1))
- enable jira global research ([#2](https://github.com/whoz-oss/coday/pull/2))

### ❤️ Thank You

- c-monot-whoz
- Vincent Palita

## 0.0.1-7 (2025-02-28)

### 🚀 Features

- bump repository to 0.0.7 ([60a21e1](https://github.com/whoz-oss/coday/commit/60a21e1))

### ❤️ Thank You

- Vincent Palita

## 0.0.1-6 (2025-02-28)

### 🚀 Features

- bump dependencies & upgrade claude-sonnet to 3.7 ([72dd074](https://github.com/whoz-oss/coday/commit/72dd074))

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
