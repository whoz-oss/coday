# User Configuration

User-level configuration stores your personal preferences and credentials. This configuration is specific to you and applies across all projects.

## Configuration Location

User configuration is stored in:
- **Linux/macOS**: `~/.config/coday/user-config.json`
- **Windows**: `%APPDATA%\coday\user-config.json`

This file should **never** be committed to version control as it contains API keys and personal settings.

## Managing User Configuration

### Via Web Interface

The easiest way to configure user settings:

1. Launch the web interface: `pnpm web`
2. Click the menu icon (hamburger menu, top-left)
3. Click "User Config" (👤 icon)
4. Edit the JSON configuration directly
5. Save changes

### Via Command Line

```bash
# View current user configuration
config user show

# Edit user configuration in your default editor
config user edit
```

### Via Direct File Edit

You can manually edit the configuration file with any text editor. The file is JSON format.

## Configuration Structure

```json
{
  "version": 1,
  "ai": {
    "providers": [
      {
        "name": "openai",
        "apiKey": "sk-...",
        "models": [
          {
            "name": "gpt-4o",
            "contextWindow": 128000
          }
        ]
      }
    ],
    "defaultProvider": "openai",
    "defaultModel": "gpt-4o"
  },
  "preferences": {
    "theme": "dark",
    "voice": {
      "input": "en-US",
      "output": "en-US-Neural2-A"
    }
  }
}
```

## Key Configuration Areas

### AI Providers

Configure API keys and model preferences:

```json
{
  "ai": {
    "providers": [
      {
        "name": "anthropic",
        "apiKey": "sk-ant-...",
        "models": [
          {
            "name": "claude-3-5-sonnet-20241022",
            "contextWindow": 200000
          }
        ]
      }
    ]
  }
}
```

**Important**: API keys are sensitive. Ensure your user config file has appropriate permissions (readable only by you).

### Interface Preferences

Personal UI preferences:

```json
{
  "preferences": {
    "theme": "dark",
    "enterToSend": false,
    "voice": {
      "input": "fr-FR",
      "output": "fr-FR-Neural2-B"
    }
  }
}
```

### User-Specific Agents

Override or add personal agents:

```json
{
  "agents": [
    {
      "name": "my-agent",
      "role": "specialized assistant",
      "provider": "openai",
      "model": "gpt-4o"
    }
  ]
}
```

## Common Tasks

### Adding an AI Provider

1. Obtain API key from provider (OpenAI, Anthropic, etc.)
2. Add to user configuration:
   ```json
   {
     "ai": {
       "providers": [
         {
           "name": "provider-name",
           "apiKey": "your-api-key-here"
         }
       ]
     }
   }
   ```

### Changing Default Model

```json
{
  "ai": {
    "defaultProvider": "anthropic",
    "defaultModel": "claude-3-5-sonnet-20241022"
  }
}
```

### Configuring Voice Settings

```json
{
  "preferences": {
    "voice": {
      "input": "en-US",
      "output": "en-US-Neural2-C"
    }
  }
}
```

## Security Best Practices

1. **Never commit** user config to version control
2. **Set restrictive permissions** on the config file (chmod 600 on Unix)
3. **Rotate API keys** regularly
4. **Use environment variables** for CI/CD instead of config files

## Troubleshooting

### Configuration Not Loading

- Check file location matches your OS
- Verify JSON syntax is valid
- Check file permissions

### API Key Not Working

- Verify the key is correct (no extra spaces)
- Check provider name matches exactly (`openai`, `anthropic`, etc.)
- Ensure you have credits/quota with the provider

## Next Steps

- [Project Configuration](./project-config.md): Setting up team-wide configuration
- [Configuration Levels](./configuration-levels.md): Understanding how configs merge
