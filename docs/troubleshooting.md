# Troubleshooting

This guide covers common issues and their solutions when working with Coday.

## Installation Issues

### Coday Won't Start

**Problem**: `npx --yes @whoz-oss/coday-web` fails or hangs

**Solutions**:
- Ensure Node.js version 22+ is installed: `node --version`
- Check port 3000 is available (or use `--port` flag)
- Clear npx cache: `npx clear-npx-cache`
- Check internet connection (npx needs to download package)
- Try with verbose logging: `npx --yes @whoz-oss/coday-web --debug`

### Build Failures

**Problem**: `pnpm nx run-many --target=build --all` fails

**Solutions**:
- Check TypeScript version compatibility
- Ensure all dependencies are installed
- Clear build cache: `pnpm nx reset`
- Check for syntax errors in recent changes

## Configuration Issues

### AI Provider Not Working

**Problem**: "API key invalid" or "Provider not found" errors

**Solutions**:
- Verify API key is correct (no extra spaces or characters)
- Check provider name matches exactly: `openai`, `anthropic`, etc.
- Ensure you have credits/quota with the provider
- Test API key directly with provider's API
- Check user config file location and permissions

### Project Not Loading

**Problem**: Coday doesn't recognize the project

**Solutions**:
- Ensure `coday.yaml` is at project root
- Verify YAML syntax (use a YAML validator)
- Check `version: 1` is present in config
- Verify `path` points to correct directory
- Check file permissions (readable by current user)

### Configuration Not Taking Effect

**Problem**: Changes to configuration don't appear

**Solutions**:
- Restart Coday after configuration changes
- Check configuration level (user vs project)
- Verify JSON/YAML syntax is valid
- Check for conflicting configurations at different levels
- Review configuration merging order (Coday → Project → User)

## Runtime Issues

### Agent Not Responding

**Problem**: Agent seems stuck or not responding to messages

**Solutions**:
- Check network connection (if using cloud AI providers)
- Verify API rate limits haven't been exceeded
- Check console/logs for error messages
- Try stopping (Ctrl+C) and restarting
- Switch to a different agent to test

### Tools Not Working

**Problem**: Agent can't use tools (read_file, write_file, etc.)

**Solutions**:
- Check file permissions (agent needs read/write access)
- Verify paths are correct (relative to project root)
- Check if tool is allowed for the agent
- Review tool configuration in agent definition
- Check for file system restrictions (sandboxing, etc.)

### Memory Issues

**Problem**: Agent seems to forget earlier conversation

**Solutions**:
- Check conversation length (may exceed context window)
- Verify memory is enabled in configuration
- Explicitly reference earlier decisions
- Consider truncating conversation and starting fresh
- Use memory feature to store important facts

### Performance Issues

**Problem**: Slow responses or high latency

**Solutions**:
- Check internet connection speed
- Try a smaller/faster model
- Reduce context window usage (shorter conversations)
- Check system resources (CPU, memory)
- Consider local model if applicable

## Interface Issues

### Web Interface Not Loading

**Problem**: Browser shows error or blank page

**Solutions**:
- Check if server is running: `pnpm web`
- Verify port 3000 is available (or specified port)
- Clear browser cache and cookies
- Try different browser
- Check console for JavaScript errors
- Verify firewall isn't blocking localhost

### Browser Not Opening

**Problem**: Server starts but browser doesn't open

**Solutions**:
- Manually open `http://localhost:3000`
- Check default browser is set
- Try different browser
- Check firewall isn't blocking localhost
- Verify port isn't already in use

### Voice Input Not Working

**Problem**: Microphone not capturing or recognizing speech

**Solutions**:
- Check browser permissions (microphone access)
- Verify microphone is set as system default
- Test microphone in other applications
- Check browser compatibility (Chrome/Edge recommended)
- Ensure HTTPS or localhost (required for microphone access)

## MCP Integration Issues

### MCP Server Not Starting

**Problem**: MCP server fails to start or isn't available

**Solutions**:
- Verify command is executable and in PATH
- Check all required arguments are provided
- Verify environment variables are set
- Enable debug mode: `debug: true` in MCP config
- Check server logs for specific errors
- Test command manually in terminal

### MCP Tools Not Available

**Problem**: Agent can't see or use MCP tools

**Solutions**:
- Verify server is enabled: `enabled: true`
- Check `allowedTools` configuration
- Ensure server started successfully (check logs)
- Verify MCP protocol version compatibility
- Test server independently before integrating

## Error Messages

### "Context window exceeded"

**Meaning**: Conversation is too long for the model's context window

**Solutions**:
- Truncate earlier messages
- Start a new conversation
- Switch to a model with larger context window
- Use memory to store important facts before truncating

### "Rate limit exceeded"

**Meaning**: Too many API requests to provider

**Solutions**:
- Wait before retrying (check provider's rate limits)
- Reduce request frequency
- Upgrade provider plan for higher limits
- Switch to different provider temporarily

### "Tool execution failed"

**Meaning**: A tool call didn't complete successfully

**Solutions**:
- Check tool-specific error message
- Verify file/path exists and is accessible
- Check permissions for the operation
- Review tool configuration
- Try executing the operation manually to debug

## Getting Help

### Debug Mode

Enable debug logging for detailed information:

```bash
# Terminal
coday --debug

# Web
pnpm web --debug
```

Debug logs show:
- API requests and responses
- Tool executions
- Configuration loading
- Error stack traces

### Checking Logs

**Terminal**: Errors printed to console

**Web**: Check browser console (F12 → Console tab)

**Server**: Check terminal where server is running

### Reporting Issues

When reporting issues, include:
- Coday version
- Operating system
- Node.js version
- AI provider and model
- Configuration (without API keys)
- Error messages (full text)
- Steps to reproduce
- Expected vs actual behavior

### Community Resources

- **GitHub Issues**: https://github.com/whoz-oss/coday/issues
- **Documentation**: https://github.com/whoz-oss/coday/tree/master/docs
- **Examples**: Check the repository for example configurations

## Prevention

### Best Practices to Avoid Issues

1. **Keep Coday updated**: Pull latest changes regularly
2. **Backup configurations**: Keep copies of working configurations
3. **Test changes**: Test configuration changes in isolation
4. **Monitor usage**: Keep an eye on API usage and costs
5. **Document custom setup**: Note any custom configurations or workarounds
6. **Regular maintenance**: Periodically review and clean up configurations

### Configuration Validation

Before committing configuration changes:
- [ ] YAML/JSON syntax is valid
- [ ] Required fields are present
- [ ] Paths are correct
- [ ] API keys are in user config (not project config)
- [ ] Test with simple conversation
- [ ] Verify all agents work

## Common Gotchas

1. **API keys in wrong config**: Keep in user config, not project config
2. **YAML indentation**: Use spaces, not tabs (2-space indentation)
3. **Case sensitivity**: Agent names, provider names are case-sensitive
4. **Path separators**: Use forward slashes (/) even on Windows
5. **Environment variables**: Need to be set before starting Coday
6. **Context window**: Conversations can't be infinitely long
7. **Network dependency**: Cloud AI providers require internet connection

*This troubleshooting guide will be expanded based on common issues reported by users.*
