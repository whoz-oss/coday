# Generic HTTP Integration

Generic HTTP integration for Coday that allows declarative configuration of HTTP APIs with support for multiple authentication methods including OAuth2.

## Features

- **Declarative endpoint definitions** in `coday.yaml`
- **Dynamic tool generation** from endpoint schemas
- **Multiple authentication methods**:
  - No authentication
  - Credentials (username + API key)
  - Bearer token
  - OAuth2 (standard + Google SSO)
- **Flexible parameter locations**: query, path, body, header
- **Automatic token refresh** for OAuth2
- **Response transformation** support (planned)

## Configuration Schema

### Basic Example (No Auth)

```yaml
integration:
  myApi:
    type: http
    baseUrl: "https://api.example.com"
    auth:
      type: none
    endpoints:
      - name: getUser
        method: GET
        path: "/users/{userId}"
        params:
          - name: userId
            type: string
            required: true
            location: path
        description: "Get user by ID"
```

### Credentials Authentication

```yaml
integration:
  myApi:
    type: http
    baseUrl: "https://api.example.com"
    username: "${env:API_USERNAME}"
    apiKey: "${env:API_KEY}"
    auth:
      type: credentials
      usernameHeader: "X-Username"
      apiKeyHeader: "X-API-Key"
    endpoints:
      - name: searchItems
        method: POST
        path: "/search"
        params:
          - name: query
            type: string
            required: true
          - name: limit
            type: number
            required: false
            default: 10
```

### Bearer Token Authentication

```yaml
integration:
  myApi:
    type: http
    baseUrl: "https://api.example.com"
    apiKey: "${env:API_TOKEN}"
    auth:
      type: bearer
      scheme: "Bearer"
    endpoints:
      - name: listItems
        method: GET
        path: "/items"
```

### OAuth2 Authentication

```yaml
integration:
  myApi:
    type: http
    baseUrl: "https://api.example.com"
    auth:
      type: oauth2
      provider: standard
      authorizationEndpoint: "https://auth.example.com/oauth/authorize"
      tokenEndpoint: "https://auth.example.com/oauth/token"
      scope: "read write"
    oauth2:
      client_id: "${env:OAUTH_CLIENT_ID}"
      client_secret: "${env:OAUTH_CLIENT_SECRET}"
      redirect_uri: "http://localhost:3000/oauth/callback"
    endpoints:
      - name: getProfile
        method: GET
        path: "/me"
```

### Google SSO OAuth2

```yaml
integration:
  myApi:
    type: http
    baseUrl: "https://api.example.com"
    auth:
      type: oauth2
      provider: google-sso
      authorizationEndpoint: "https://accounts.google.com/o/oauth2/v2/auth"
      tokenEndpoint: "https://oauth2.googleapis.com/token"
      scope: "openid email profile"
    oauth2:
      client_id: "${env:GOOGLE_CLIENT_ID}"
      client_secret: "${env:GOOGLE_CLIENT_SECRET}"
      redirect_uri: "http://localhost:3000/oauth/callback"
    endpoints:
      - name: searchConcepts
        method: POST
        path: "/search"
```

## Complete PSO Example

```yaml
integration:
  PSO:
    type: http
    baseUrl: "https://pso.whoz.com/api"
    auth:
      type: oauth2
      provider: google-sso
      authorizationEndpoint: "https://accounts.google.com/o/oauth2/v2/auth"
      tokenEndpoint: "https://oauth2.googleapis.com/token"
      scope: "openid email profile"
    oauth2:
      client_id: "${env:PSO_GOOGLE_CLIENT_ID}"
      client_secret: "${env:PSO_GOOGLE_CLIENT_SECRET}"
      redirect_uri: "http://localhost:3000/oauth/callback"
    
    endpoints:
      - name: searchConcepts
        method: POST
        path: "/search"
        params:
          - name: term
            type: string
            required: true
            description: "Search term"
          - name: languages
            type: array
            required: false
            description: "Language codes to search in"
            items:
              type: string
          - name: conceptType
            type: string
            required: false
            description: "Type of concept to search for"
        description: "Search for concepts by term"
      
      - name: getConcept
        method: GET
        path: "/concepts/{conceptId}"
        params:
          - name: conceptId
            type: string
            required: true
            location: path
            description: "Concept ID"
        description: "Get concept details by ID"
      
      - name: getConceptChildren
        method: GET
        path: "/hierarchy/children/{conceptId}"
        params:
          - name: conceptId
            type: string
            required: true
            location: path
            description: "Parent concept ID"
        description: "Get child concepts of a parent concept"
```

## Parameter Types

Supported parameter types:
- `string`: Text value
- `number`: Numeric value
- `boolean`: True/false value
- `array`: Array of values (specify `items.type`)
- `object`: Object value (for complex structures)

## Parameter Locations

- `query`: URL query parameter (default for GET/DELETE)
- `path`: Path parameter (e.g., `/users/{userId}`)
- `body`: Request body parameter (default for POST/PUT/PATCH)
- `header`: HTTP header parameter

## Generated Tools

For each endpoint, a tool is generated with the name pattern:
```
{integrationName}_{endpointName}
```

Examples:
- `PSO_searchConcepts`
- `PSO_getConcept`
- `PSO_getConceptChildren`

## OAuth2 Flow

1. **First use**: User is prompted to authorize via browser
2. **Authorization**: User authorizes and is redirected with code
3. **Token exchange**: Code is exchanged for access token
4. **Token storage**: Tokens saved in user config
5. **Automatic refresh**: Tokens refreshed when expired
6. **Subsequent uses**: Existing tokens used automatically

## Configuration in User Config

OAuth2 tokens are stored in user config (not project config):

```yaml
integration:
  PSO:
    oauth2:
      client_id: "xxx"
      client_secret: "yyy"
      redirect_uri: "http://localhost:3000/oauth/callback"
      tokens:
        access_token: "zzz"
        refresh_token: "aaa"
        expires_at: 1234567890
        token_type: "Bearer"
        scope: "openid email profile"
```

## Implementation Notes

### Phase 1 (Current) ✅
- [x] Schema definition for HTTP endpoints
- [x] Runtime tool generation from config
- [x] Credentials authentication
- [x] Bearer token authentication
- [x] OAuth2 standard flow
- [x] OAuth2 Google SSO support
- [x] Token storage in user config
- [x] Automatic token refresh

### Phase 2 (Future)
- [ ] Response payload filtering/transformation (JSONPath)
- [ ] Request/response validation (JSON Schema)
- [ ] Complex authentication schemes (JWT, custom)
- [ ] Retry logic with exponential backoff
- [ ] Rate limiting support
- [ ] Custom error handling per endpoint

## Usage in Agents

Once configured, tools are automatically available to agents:

```yaml
agents:
  - name: pso-assistant
    integrations:
      - PSO  # All PSO endpoints become available as tools
```

The agent can then use tools like:
```
PSO_searchConcepts({ term: "software engineering", languages: ["en", "fr"] })
PSO_getConcept({ conceptId: "12345" })
```

## Debugging

Enable debug mode to see HTTP requests:
```bash
pnpm start --debug
```

Debug logs will show:
- Tool generation from endpoints
- HTTP request details (method, URL)
- OAuth initialization and token refresh
- Error details

## Security Notes

- **Environment variables**: Use `${env:VAR_NAME}` for sensitive values
- **Token storage**: OAuth tokens stored in user config (encrypted at rest)
- **Credentials**: API keys stored in project/user config (masked in UI)
- **HTTPS required**: OAuth2 flows require HTTPS in production

## Related

- Existing OAuth2 integration: `libs/integrations/basecamp/`
- Credentials integrations: `libs/integrations/jira/`, `libs/integrations/gitlab/`
- Integration service: `libs/service/integration.service.ts`
