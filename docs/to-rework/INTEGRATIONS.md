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

### Zendesk

Integration enables Zendesk Help Center article search and retrieval. All fields required:

- API url: your Zendesk subdomain (not the full URL, just the subdomain)
  ```
  mycompany
  ```
  For example, if your help center is at `https://mycompany.zendesk.com`, enter `mycompany`
- Username: your Zendesk email address associated to the API token
- API key: Zendesk API token (create in Admin Center -> Apps and integrations -> Zendesk API -> Add API token) ⚠️ tied to the user.

### Basecamp

Integration enables Basecamp project and message management through OAuth2 authentication.

#### Setup

1. **Create a Basecamp OAuth2 application**:
   - Go to [Basecamp Launchpad Integrations](https://launchpad.37signals.com/integrations)
   - Click "Register one now" to create a new OAuth2 application
   - Fill in the application details:
     - **Name**: Your application name (e.g., "Coday Integration")
     - **Company/Organization**: Your company name
     - **Website**: Your website or GitHub repository
     - **Redirect URI**: See configuration below

2. **Configure redirect URIs**:
   
   Register the appropriate redirect URI(s) based on your environment:
   
   - **Development**: `http://localhost:3001/oauth/callback`
   - **Production**: `https://your-domain.com/oauth/callback`
   
   You can register multiple redirect URIs to support both environments.

3. **Configure in Coday**:

   **Project-level configuration** (in `coday.yaml`):
   ```yaml
   integration:
     BASECAMP:
       username: "your_client_id_here"        # Client ID from Basecamp app
       apiKey: "your_client_secret_here"    # Client Secret from Basecamp app
       oauth2:
         redirect_uri: "http://localhost:3001/oauth/callback"  # Or production URL
   ```

   **User-level configuration** (tokens are stored automatically after authentication):
   
   After your first OAuth authentication, Coday automatically stores tokens in `~/.coday/users/{username}/user.yml`:
   ```yaml
   projects:
     your-project:
       integration:
         BASECAMP:
           oauth2:
             tokens:
               access_token: "..."      # Stored securely
               refresh_token: "..."     # For token renewal
               expires_at: 1735689600000
             account_href: "https://3.basecampapi.com/xxxxx"  # Selected account
             account_name: "Your Company"                      # For display
   ```
