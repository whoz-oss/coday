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
- Review configuration merging order (Coday → Project → User)
- Check config file locations

## Runtime Issues

### Voice Input Not Working

**Problem**: Microphone not capturing or recognizing speech

**Solutions**:
- Check browser permissions (microphone access) and microphone selection
- Check selected language
- Test microphone in other applications

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

### Performance Issues

**Problem**: Slow responses or high latency

**Solutions**:
- Check internet connection speed
- Try a smaller/faster model
- Reduce context window usage (shorter conversations)
- Check system resources (CPU, memory)
- Consider local model if applicable

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
- Check agent's `integrations` configuration
- Ensure server started successfully (check logs)

## Getting Help

### Debug Mode

Enable debug logging for detailed information with :
- `--debug` in command line starting Coday
- type and send `debug true` in a conversation

### Checking Logs

**Web**: Check browser console (F12 → Console tab)

**Server**: Check terminal where server is running

### Reporting Issues

When reporting issues, include:
- Error messages (full text)
- Expected vs actual behavior
- Configuration (without API keys)
- Steps to reproduce
- AI provider and model
- Coday version
- Operating system
- Node.js version

### Community Resources

- **GitHub Issues**: https://github.com/whoz-oss/coday/issues
- **Documentation**: https://github.com/whoz-oss/coday/tree/master/docs
- **Examples**: Check the repository for example configurations

## Common Gotchas

1. **API keys in wrong config**: Keep in user config, not project config
2. **YAML indentation**: Use spaces, not tabs (2-space indentation)
3. **Case sensitivity**: Agent names, provider names are case-sensitive
4. **Path separators**: Use forward slashes (/) even on Windows
5. **Environment variables**: Need to be set before starting Coday
6. **LLM provider health**: if Anthropic/OpenAI is down, no agents...
7. **Network dependency**: Cloud AI providers require internet connection
