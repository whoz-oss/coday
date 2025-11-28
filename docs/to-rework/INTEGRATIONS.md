# 🔌 Integrations

Coday can interface with different APIs and tools through integrations. Each type has its own configuration requirements
and setup process.

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

### Zendesk Articles

Integration enables Zendesk Help Center article search and retrieval. All fields required:

- API url: your Zendesk subdomain (not the full URL, just the subdomain)
  ```
  mycompany
  ```
  For example, if your help center is at `https://mycompany.zendesk.com`, enter `mycompany`
- Username: your Zendesk email address
- API key: Zendesk API token (create in Admin Center -> Apps and integrations -> Zendesk API -> Add API token)