# Coday Configuration: Scopes and Best Practices

Coday uses a layered configuration model, allowing you to define global defaults, user-specific settings, and project-specific overrides. Understanding these levels ensures secure handling of sensitive data, optimal sharing of best practices, and flexible agentic workflows.

By decreasing order of priority:
1. User-Level, overrides all
2. Project-Level, overrides Coday-Level
3. Coday.yaml-Level, basis for the project

---

## 1. Project Configuration: `coday.yaml`

**Location:**  
- Must reside inside your project directory (anywhere under the project root, not above or outside through symlinks).

**Purpose:**  
- This file is the **canonical project knowledge base** for Coday.
- It describes the project’s goals, context, agents, and workflows.
- It should be version-controlled and shared with your team.

**What to configure here:**  
- **Project description, documentation links, and rules**
- **Agent definitions** (core project agents, their instructions, tool access, and any project-specific roles)
- **Project-level integration settings** (e.g., tools to expose to agents, documentation files to include)
- **Prompt chains, workflows, and custom handlers**

**What NOT to configure here:**  
- **Sensitive Data:** Never store API keys, access tokens, user secrets, or credentials in this file.
- **User-specific or system-specific paths:** Avoid settings that differ from one developer/machine to another.

**Best practice:**  
- Treat `coday.yaml` as a project handbook for intelligent agents—keep it descriptive, up to date, and portable.

---

## 2. User-Level Configuration

**Location:**  
- Automatically managed in your home directory at `~/.coday/users/[your username]/user.yaml`.

**Purpose:**  
- Stores user preferences, credentials, integration secrets, and personalized agent settings.
- Never version-controlled or shared.

**What to configure here:**  
- **API keys and secrets** for LLM providers (OpenAI, Anthropic, etc.).
- **Personal agent customizations** (e.g., your own default agent, aliases, user-specific agent files).
- **Project list and recently accessed projects**.
- **Default user preferences**.

**What NOT to configure here:**  
- **Project-wide knowledge, agent definitions, or workflows:** Any configuration needed by the whole team or describing the project must remain in `coday.yaml`.

**Best practice:**  
- Never share or commit your `~/.coday/` folder.
- Use commands like `coday ai apikey` to manage your keys securely.

---

## 3. Project-Scoped

**How it works:**  
- Some settings (API keys, agent tweaks) can be set at the project level (using CLI with `--project` flag), stored in a `.coday/` folder within the project root.
- These are overriden by user-level settings for that project, they are meant for setting some part of the configuration for all the users of the project.

**What to configure here:**  
- **Project-specific secrets for all users** or tokens (only if required and if not shared).
- **Overrides for agent behavior** relevant only for the current project and only for you (rare, advanced use).

**What NOT to configure here:**  
- Never store individual secrets nor individual api keys.

**Best practice:**  
- Use project-scoped overrides only for advanced, narrowly-scoped needs that cannot be managed at coday level or at user level.
- Avoid committing project-local `.coday/` folders if they contain secrets.

---

## Practical Command Usage

- **Default (user level):**
  ```sh
  coday ai list
  coday ai apikey
  ```
- **Project-level (shared, in coday.yaml):**
  ```sh
  # Edit coday.yaml (manually or via 'coday ai add --project')
  ```
- **Project-scoped override (for you, not shared):**
  ```sh
  coday ai apikey --project
  ```

