# Web-Agent: Enabling AI-Driven Web Research

## Context and Purpose

In the evolving landscape of AI agents, the ability to access and comprehend web content represents a fundamental
capability. The Web-Agent concept emerges as a crucial component for Coday, particularly as a foundational step toward
more complex integrations like API-agents. This agent would provide autonomous web research capabilities, enabling both
direct user queries and inter-agent information gathering.

The primary challenge lies not in accessing web content itself, but in doing so efficiently and intelligently. Modern
web applications, with their client-side rendering and dynamic content, require more sophisticated approaches than
simple HTTP requests. Additionally, the integration must balance capability with system complexity, ensuring the agent
enhances rather than complicates the existing architecture.

## Technical Foundation

Microsoft's Playwright emerges as a compelling foundation for the Web-Agent implementation. As a free, open-source
solution, it provides robust capabilities for handling modern web applications while offering the potential for a
simplified integration interface. Despite its extensive API surface, primarily designed for testing, the Web-Agent would
utilize only a core subset of functionality focused on browser automation and content extraction.

The integration would maintain a browser instance per AI thread, ensuring clean separation of contexts while avoiding
unnecessary resource overhead. This approach aligns with Coday's existing architectural patterns while providing the
necessary isolation for concurrent operations. Playwright's cross-platform compatibility and active maintenance by
Microsoft provide confidence in its long-term viability as a foundation.

## Integration Architecture

The Web-Agent would be implemented as a dedicated integration within Coday's framework, similar to existing integrations
like GitLab or Jira. This approach provides clear boundaries for configuration and lifecycle management while
maintaining consistency with the broader system architecture. The integration would expose a simplified interface,
hiding the complexity of browser automation behind a clean API focused on content retrieval and search operations.

Configuration would focus on essential parameters such as search source selection (e.g., Google, DuckDuckGo, GitHub) and
basic browser instance settings. This configuration-driven approach allows for flexibility in deployment while
maintaining operational simplicity. The integration would handle browser instance management, content extraction, and
basic bot detection avoidance internally, presenting a streamlined interface to the agent layer.

## Token Economy and Usage Patterns

The token cost of web content processing presents a significant consideration in the Web-Agent's design. While large
language models excel at understanding and synthesizing web content, the token consumption for processing entire web
pages could become substantial. This economic reality necessitates careful consideration of when and how to employ web
research capabilities.

Two primary usage patterns emerge: direct user queries and agent-to-agent research. Direct user queries, while
potentially token-intensive, could provide valuable synthesis of information across multiple sources. Agent-to-agent
research, particularly for specific technical queries like API documentation or changes, presents cases where the token
cost is justified by the value of the information obtained. The system should provide clear guidance or warnings about
token usage while maintaining flexibility for necessary research operations.

## Authentication and Access Patterns

Web content access presents several authentication scenarios. While focusing initially on publicly accessible content
provides the simplest path forward, the potential to leverage user browser sessions offers interesting possibilities for
accessing authenticated resources. This capability, while powerful, raises important considerations around privacy and
security that would need careful examination before implementation.

The system must also handle modern web challenges such as bot detection and rate limiting. While Playwright provides
mechanisms for managing these issues, the implementation must balance aggressive crawling capabilities with responsible
web citizenship. This includes respecting robots.txt directives and implementing appropriate delays between requests.

## Future Considerations

As the Web-Agent evolves, several areas merit consideration for future enhancement. The potential for content caching
could help manage token usage for frequently accessed resources. Integration with other agents, particularly for API
documentation and change detection, could provide valuable synergies. The system might also evolve to support more
sophisticated research patterns, potentially including the ability to validate information across multiple sources.

The relationship between the Web-Agent and future API-agents presents particularly interesting possibilities. As
API-agents evolve to understand and interact with specific APIs, the Web-Agent could serve as a crucial tool for
maintaining their knowledge base, particularly around API changes and updates. This symbiotic relationship could enhance
the overall system's ability to maintain accurate and current API interactions.

## Implementation Strategy

The implementation of the Web-Agent should proceed deliberately, starting with core capabilities and expanding based on
demonstrated value. Initial focus should remain on basic web research capabilities, particularly in support of other
agents' needs. The integration should maintain simplicity while providing clear paths for future enhancement.

Key aspects of the initial implementation include:

- Clean integration with Coday's existing architecture
- Simplified interface hiding Playwright complexity
- Clear token usage patterns and guidelines
- Support for basic research operations
- Foundation for future API-agent support

## Conclusion

The Web-Agent represents a significant capability for Coday, providing a foundation for autonomous web research while
supporting future enhancements like API-agents. While the implementation presents challenges around token economy and
system complexity, careful architecture and clear use case definition can ensure the agent provides value while
maintaining system simplicity. The choice of Playwright as a foundation balances capability with integration complexity,
providing a robust base for this crucial system component.

## Technical Appendix: Content Extraction Patterns

This appendix provides practical code snippets and patterns for effective web content extraction using Playwright. These
examples serve as reference points for future implementation.

### Basic Content Extraction

```typescript
async function extractMainContent(page: Page): Promise<string> {
  // Wait for the main content to be available
  // Adjust selector based on common patterns or specific sites
  await page.waitForSelector('main, article, .content, #content', {
    state: 'attached',
    timeout: 10000
  });
  
  return page.evaluate(() => {
    // Remove noisy elements
    const selectorsToRemove = [
      'nav',
      'header',
      'footer',
      '.ads',
      '.cookie-notice',
      '.popup',
      'script',
      'style'
    ];
    
    selectorsToRemove.forEach(selector => {
      document.querySelectorAll(selector).forEach(el => el.remove());
    });
    
    // Get main content
    const mainContent = document.querySelector('main, article, .content, #content');
    return mainContent ? mainContent.innerText : document.body.innerText;
  });
}
```

### Dynamic Content Handling

```typescript
async function waitForDynamicContent(page: Page): Promise<void> {
  // Wait for network to be idle (no requests for 500ms)
  await page.waitForLoadState('networkidle');
  
  // Additional checks for dynamic content
  await Promise.race([
    // Wait for specific indicator of content
    page.waitForSelector('[data-loaded="true"]'),
    // Timeout after reasonable wait
    new Promise(resolve => setTimeout(resolve, 5000))
  ]);
  
  // Optional: wait for any animations to complete
  await page.evaluate(() => new Promise(resolve => {
    const checkAnimations = () => {
      const animations = document.getAnimations();
      if (animations.length === 0) resolve(undefined);
      else setTimeout(checkAnimations, 100);
    };
    checkAnimations();
  }));
}
```

### Search Result Extraction

```typescript
interface SearchResult {
  title: string;
  url: string;
  snippet: string;
}

async function extractSearchResults(page: Page): Promise<SearchResult[]> {
  // Common search engine result patterns
  const resultSelectors = {
    google: {
      container: '.g',
      title: 'h3',
      link: 'a',
      snippet: '.VwiC3b'
    },
    duckduckgo: {
      container: '.result',
      title: '.result__title',
      link: '.result__url',
      snippet: '.result__snippet'
    }
  };
  
  // Detect search engine and use appropriate selectors
  const engine = await detectSearchEngine(page);
  const selectors = resultSelectors[engine];
  
  await page.waitForSelector(selectors.container);
  
  return page.evaluate((sel) => {
    return Array.from(document.querySelectorAll(sel.container))
      .map(result => ({
        title: result.querySelector(sel.title)?.textContent?.trim() || '',
        url: result.querySelector(sel.link)?.getAttribute('href') || '',
        snippet: result.querySelector(sel.snippet)?.textContent?.trim() || ''
      }))
      .filter(r => r.url && r.title); // Ensure valid results only
  }, selectors);
}
```

### Documentation-Specific Extraction

```typescript
async function extractDocumentation(page: Page): Promise<string> {
  // Common documentation site patterns
  const docSelectors = [
    // API documentation
    '.api-documentation',
    '.swagger-ui',
    '.redoc',
    // General documentation
    '.markdown-body',
    '.documentation-content',
    '#readme'
  ];
  
  // Wait for any selector to be available
  await Promise.race(
    docSelectors.map(selector =>
      page.waitForSelector(selector, {timeout: 5000})
        .catch(() => null)
    )
  );
  
  return page.evaluate((selectors) => {
    // Try each selector
    for (const selector of selectors) {
      const element = document.querySelector(selector);
      if (element) {
        // Clean up code blocks for better token efficiency
        const codeBlocks = Array.from(element.querySelectorAll('pre code'));
        codeBlocks.forEach(block => {
          // Keep only first few lines of long code examples
          const lines = block.textContent?.split('\n') || [];
          if (lines.length > 10) {
            block.textContent = lines.slice(0, 10).join('\n') + '\n// ...';
          }
        });
        
        return element.innerText;
      }
    }
    // Fallback to main content extraction
    return document.body.innerText;
  }, docSelectors);
}
```

### Error Handling and Retry Logic

```typescript
async function robustPageAccess(
  browser: Browser,
  url: string,
  options = {maxRetries: 3, timeout: 30000}
): Promise<string> {
  let attempt = 0;
  
  while (attempt < options.maxRetries) {
    const page = await browser.newPage();
    try {
      // Set reasonable viewport
      await page.setViewport({width: 1280, height: 800});
      
      // Handle common navigation errors
      await Promise.race([
        page.goto(url, {
          waitUntil: 'networkidle',
          timeout: options.timeout
        }),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error('Navigation timeout')),
            options.timeout)
        )
      ]);
      
      // Extract content
      const content = await extractMainContent(page);
      await page.close();
      return content;
    
    } catch (error) {
      await page.close();
      attempt++;
      
      // If it's the last attempt, throw the error
      if (attempt === options.maxRetries) throw error;
      
      // Wait before retry (exponential backoff)
      await new Promise(resolve =>
        setTimeout(resolve, Math.pow(2, attempt) * 1000)
      );
    }
  }
  
  throw new Error('Failed to access page after maximum retries');
}
```

These patterns provide a foundation for robust web content extraction while handling common challenges such as dynamic
content, different site structures, and potential errors. The actual implementation might need adjustment based on
specific use cases and encountered websites, but these examples demonstrate key considerations and approaches for
effective content extraction.