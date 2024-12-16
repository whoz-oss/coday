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
