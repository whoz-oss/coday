# Zendesk Articles Integration

This integration provides tools to search and retrieve articles from Zendesk Help Center.

## Configuration

Configure the integration using the CLI:

```bash
# User level (personal credentials)
config integration add --name=ZENDESK_ARTICLES

# Project level (shared across team)
config integration add --name=ZENDESK_ARTICLES --project
```

### Required Fields

- **API URL**: Your Zendesk subdomain (e.g., `mycompany` for `https://mycompany.zendesk.com`)
- **Username**: Your Zendesk email address
- **API Key**: Zendesk API token (Admin Center -> Apps and integrations -> Zendesk API -> Add API token)

## Available Tools

### searchZendeskArticles

Search for articles in Zendesk Help Center by query text.

**Parameters:**
- `query` (required): Search text. Can include multiple words. Zendesk searches across article titles and content.
- `locale` (optional): Locale code (e.g., `en-us`, `fr`). Use `*` to search across all locales. Defaults to account's default locale.

**Returns:**
- Array of matching articles with:
  - `id`: Article ID (for use with retrieveZendeskArticle)
  - `title`: Article title
  - `snippet`: Text excerpt with matching keywords
  - `url`: Direct link to article in Help Center
  - `locale`: Article language
  - `updated_at`: Last update timestamp

**Example:**
```typescript
searchZendeskArticles({
  query: "password reset",
  locale: "en-us"
})
```

### retrieveZendeskArticle

Retrieve the full content of a Zendesk article by ID.

**Parameters:**
- `articleId` (required): Zendesk article ID (numeric string)
- `locale` (optional): Locale code. If not provided, returns article in default locale.

**Returns:**
- Full article object with:
  - `id`: Article ID
  - `title`: Article title
  - `body`: Full HTML content
  - `author_id`: Author user ID
  - `section_id`: Section ID
  - `locale`: Article language
  - `created_at`: Creation timestamp
  - `updated_at`: Last update timestamp
  - `html_url`: Direct link to article
  - `draft`: Whether article is in draft mode
  - `promoted`: Whether article is promoted
  - `vote_sum`: Article vote score
  - `comments_disabled`: Whether comments are disabled

**Example:**
```typescript
retrieveZendeskArticle({
  articleId: "35467",
  locale: "en-us"
})
```

## API Details

This integration uses the Zendesk Help Center API v2:

- **Search endpoint**: `GET /api/v2/help_center/articles/search`
- **Article endpoint**: `GET /api/v2/help_center/{locale}/articles/{article_id}`
- **Authentication**: HTTP Basic Auth with `{email}/token:{api_token}`

## Typical Workflow

1. **Search** for articles using keywords
2. **Review** search results (titles and snippets)
3. **Retrieve** full content for relevant articles
4. **Process** article HTML content as needed

## Notes

- Search results are limited to articles the authenticated user can access
- HTML content may contain unsafe tags/attributes that are filtered by Zendesk
- Article snippets include `<em>` tags around matching keywords (automatically removed by search function)
- Locale is optional for admins/agents but required for end users
