# Work in Progress

This file tracks ongoing ideas and tasks in a lightweight format for easy manual editing.

## 🚀 High Priority / In Progress
*Ideas that are actively being worked on or should be addressed soon*

- [x] expand CodayEvents to serve as messages in AiThread for ClaudeClient, with mapping and conversion when getting the messages
- [~] move to stateless AiClient implementations, using only AiThread and Agent as input
- [ ] rebuild documentation, it sucks for users...
- [ ] async tool outputs: answer the LLM the result will come later, and send it for action when finished.
- [ ] open bash (under an integration flag ?)
- [ ] enable memory to be configured on a remote and shared DB => find a DB provider + build interface & configuration needed.
- [ ] extract AiThread from the inside of ClaudeClient for more general use
- [ ] establish AiThread lifecycle for later (naming, saving, memory extraction before summarization)
- [ ] move to a full state-less API for ai.client.ts, migrate smoothly
- [ ] expose internal agents for summarization, memory extraction and curation, simple agent rag. They have dedicated configuration & tools.
- [ ] make threads save-able (with auto-naming), auto-save, load last one, update thread handler


## 💡 Ideas Pool
*Unsorted ideas to be categorized or prioritized later*

- expand memory selection through vector RAG or simple tags (filter all tags for given prompt, then filter memories for prompt) to raise the max memories
- allow custom extensions: how, where ?
- agent spawned per project or prompt ?


## 🛠️ Technical Improvements
*Code, architecture, and technical debt related ideas*

- [ ] add fucking tests
- [ ] modularize properly the application (yarn workspaces ?) to solve the clumsy relative imports
- [ ] make an npm package (or packages, do after yarn workspaces ?)
- [ ] build with a real frontend framework or use a chat UI library
- [ ] add a web scraping integration (select an existing tool)
- [ ] split more coday.yml: move out prompts and/or scripts out, later assistant => agent section.
- [ ] allow nestable custom prompts
 

## 🎨 UX/Interface
*User experience and interface related ideas*

- [ ] add a stop button that ... stops the LLM loop and allow redirecting the thread
- [ ] add a retry button to wipe a section of the thread and try again
- [ ] select projects through a menu in the UI (instead of the choice component)


## 🔮 Future Possibilities
*Longer-term ideas or those needing more research*

- from a web scraper, have an auto-tooling capability in Coday repo: take a API, its openapi, its docs, get the full integration module.
- voice interaction: let the user speak, answer with text/voice
- image
- slack UI with an integration (implies running on a server/docker image)
- auto-prompts: generate prompts for the project from a past interaction.


## 📝 Notes
*Random thoughts, context, or temporary information*

- Note 1...

---
Legend:
- [ ] = Todo
- [~] = In Progress
- [x] = Done
- No checkbox = Just an idea/note