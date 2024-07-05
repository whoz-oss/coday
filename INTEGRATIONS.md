# Integrations

Coday can interface with different APIs and tools that are managed as an `integration`.

Each integration is attached to a project and managed through the command `config edit-integration`. Each integration is defined by:

- name: among enum values
- API url
- username
- API key / token


## OpenAI

Integration is required to use all AI functionalities of Coday. Configuration relies only on your API key:

- API url: nothing
- username: nothing
- API key / token: paste the API key to get on openai administration / settings page, tied to your organization / account.


## Git

Integration is just there to allow (or disable if not set) all git tools. As all git operations are runned with your local git command, no more configuration is required:

- API url: nothing
- username: nothing
- API key / token: nothing


## Jira

Integration enables Jira tools, all fields are required:

- API url: the API root url of the instance you use, ex: `https://[organization name].atlassian.net`
- username: your email
- API key / token: the personal api token to create in atlassian interface (around your profile / security)


## GitLab

Integration enables GitLab tools, all fields are required:

- API url: the API root url of the path to your project, ex: `https://gitlab.com/api/v4/projects/[projectId]`. You can find the projectId on the homepage of the project -> 3 dots menu -> copy projectId: XXXXX.
- username: your email
- API key / token: the personal api token to create in atlassian interface (around your profile / security)