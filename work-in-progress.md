# Work in Progress

This file tracks ongoing ideas and tasks in a lightweight format for easy manual editing.

## 🚀 High Priority / In Progress
*Ideas that are actively being worked on or should be addressed soon*

- [ ] rebuild documentation, it sucks for users...
- [ ] enhance memory model: agentName, TTL, full handler for by-user management
- [ ] enable memory to be configured on a remote and shared DB => find a DB provider + build interface & configuration
  needed.

## 📋 Backlog

*Topics ordered to address near-term*

- [ ] re-enable cross-agent calling
- [ ] expose internal agents for summarization, memory extraction and curation, simple agent rag. They have dedicated
  configuration & tools.
- [~] make threads save-able (with auto-naming), auto-save, load last one, update thread handler


## 💡 Ideas Pool
*Unsorted ideas to be categorized or prioritized later*

- expand memory selection if above a threshold through agent RAG to raise the max memories
- allow custom extensions: how, where ?
- agent spawned per project or prompt dynamically ?


## 🛠️ Technical Improvements
*Code, architecture, and technical debt related ideas*

- [~] add fucking tests
- [ ] build with a real frontend framework or use a chat UI library
- [ ] add a web scraping integration (select an existing tool)
- [ ] split more coday.yml: move out prompts and/or scripts out, later assistant => agent section.
- [ ] allow nestable custom prompts
 

## 🎨 UX/Interface
*User experience and interface related ideas*

- [ ] add a retry button to wipe a section of the thread and try again
- [ ] better choice component: larger, center, keyboard navigable
- [ ] ditch header, have left top corner icon that serves as menu


## 🔮 Future Possibilities
*Longer-term ideas or those needing more research*

- from a web scraper, have an auto-tooling capability in Coday repo: take a API, its openapi, its docs, get the full integration module.
- voice interaction: let the user speak, answer with text/voice
- API integration through tools in database and dedicated agent wrapping: lets user add tools
- image, xls, pdf, video handling
- slack UI with an integration (implies running on a server/docker image)
- auto-prompts: generate prompts for the project from a past interaction.


---
Legend:
- [ ] = Todo
- [~] = In Progress
- [x] = Done
- No checkbox = Just an idea/note