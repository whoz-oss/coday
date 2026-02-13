# Integration Guide for HTTP Tools

This document explains how to integrate the HTTP tools into Coday's Toolbox.

## Step 1: Add to Toolbox Registry

In `libs/agent/src/lib/toolbox.ts`, add the HTTP tools to the factory constructors registry:

```typescript
import { HttpTools } from '@coday/integrations-http'

// In the constructor, after existing integration registrations:
this.factoryConstructors.set(
  HttpTools.TYPE,
  (name, config) => new HttpTools(interactor, services.integration, services.user, name, config)
)
```

## Step 2: Update tsconfig.base.json

Add the path mapping for the new library:

```json
{
  "compilerOptions": {
    "paths": {
      "@coday/integrations-http": ["libs/integrations/http/src/index.ts"]
    }
  }
}
```

## Step 3: Add to package.json workspaces (if needed)

Ensure the library is included in the workspace:

```json
{
  "workspaces": [
    "libs/integrations/http"
  ]
}
```

## Step 4: Build the library

```bash
pnpm nx build @coday/integrations-http
```

## Step 5: Test with PSO configuration

Create a test project with the PSO configuration from `examples/pso-config.example.yml`.

### Environment Variables

Set the required environment variables:

```bash
export PSO_GOOGLE_CLIENT_ID="your-client-id"
export PSO_GOOGLE_CLIENT_SECRET="your-client-secret"
```

### Test the integration

1. Start Coday with the project:
   ```bash
   pnpm start
   ```

2. Select the project with PSO integration

3. Try using a PSO tool:
   ```
   Use PSO_searchConcepts to search for "software engineering"
   ```

4. The first time, you'll be prompted to authorize via Google OAuth2

5. After authorization, the tool should work and return results

## OAuth2 Callback Setup

### Local Development

The OAuth2 callback URL should be:
```
http://localhost:3000/oauth/callback
```

This needs to be configured in your Google Cloud Console for the OAuth2 app.

### Production

For production, update the `redirect_uri` in the integration config to match your production URL:
```yaml
oauth2:
  redirect_uri: "https://your-domain.com/oauth/callback"
```

## Debugging

Enable debug mode to see detailed logs:

```bash
pnpm start --debug
```

This will show:
- Tool generation from endpoints
- HTTP request details
- OAuth initialization and token refresh
- Error details

## Complete Toolbox Integration

Here's the complete change needed in `toolbox.ts`:

```typescript
import { HttpTools } from '@coday/integrations-http'

export class Toolbox implements Killable {
  constructor(
    private readonly interactor: Interactor,
    private readonly services: CodayServices,
    agentFind: (nameStart: string | undefined, context: CommandContext) => Promise<Agent | undefined>,
    agentSummaries: () => AgentSummary[]
  ) {
    // ... existing code ...

    // Add HTTP integration (after existing integrations)
    this.factoryConstructors.set(
      HttpTools.TYPE,
      (name, config) => new HttpTools(interactor, services.integration, services.user, name, config)
    )
  }
}
```

That's it! The HTTP integration will now be available for any project that defines HTTP integrations in their `coday.yaml`.

## Multiple HTTP Integrations

You can have multiple HTTP integrations in the same project:

```yaml
integration:
  PSO:
    type: http
    baseUrl: "https://pso.whoz.com/api"
    # ... PSO config ...
  
  InternalAPI:
    type: http
    baseUrl: "https://internal.company.com/api"
    # ... Internal API config ...
  
  PublicAPI:
    type: http
    baseUrl: "https://api.example.com"
    # ... Public API config ...
```

Each will generate its own set of tools:
- `PSO_searchConcepts`, `PSO_getConcept`, etc.
- `InternalAPI_getData`, `InternalAPI_postData`, etc.
- `PublicAPI_listItems`, `PublicAPI_getItem`, etc.
