# 🔌 Integrations

Coday can interface with different APIs and tools through integrations. Each type has its own configuration requirements
and setup process.

## 🤖 AI Providers

AI capabilities are configured at the user or project level. Supported providers:

### Available Providers

| Provider  | Description                 | Status |
|-----------|-----------------------------|--------|
| Anthropic | Claude models (recommended) | ✨      |
| OpenAI    | GPT models                  | ✅      |
| Gemini    | Google's Gemini models      | 🚧     |

### Configuration

Two ways to configure API keys:

1. **User Configuration** (recommended)

Use the integrated command `config ai user` at user level or `config ai project` to set it at project-level (⚠️ will be used by all users on the project). Then follow the instructions.

2. **Environment Variables** (temporary override)
   ```bash
   # Only overrides existing configuration!
   export ANTHROPIC_API_KEY="sk-xxx"
   export OPENAI_API_KEY="sk-xxx"
   export GEMINI_API_KEY="xxx"
   ```

> 📝 **Note**: Environment variables only override existing configurations. A provider must be configured to be available.

## 🛠️ Project Integrations

These integrations are configured per project through `config integration user` at user level or `config integration project` to set it at project-level (⚠️ will be used by all users on the project). Then follow the instructions.

### Git

Integration enables git-related tools. No configuration required:

- API url: N/A
- Username: N/A
- API key: N/A

### GitLab

Integration enables GitLab tools. All fields required:

- API url: the API root url of your project
  ```
  https://gitlab.com/api/v4/projects/[projectId]
  ```
  > 💡 Find projectId in project homepage -> ⋮ menu -> copy projectId
- Username: your email
- API key: personal access token (create in GitLab settings)

### Jira

Integration enables Jira tools. All fields required:

- API url: your instance root url
  ```
  https://[organization].atlassian.net
  ```
- Username: your email
- API key: personal API token (create in Atlassian settings)

### Confluence

Integration enables Confluence tools. All fields required:

- API url: your instance root url
  ```
  https://[organization].atlassian.net
  ```
- Username: your email
- API key: personal API token (same as Jira)