# Orchestration Study

## Context

The space between user runs and agent calls in Coday currently lacks clear structure, with command execution loops
scattered across different components and tools like `subTask` sometimes leading to unwanted behaviors. As AI models
evolve but remain not quite capable enough for fully autonomous execution, we need a flexible orchestration system to
maximize their effectiveness through various patterns and strategies.

## Core Concepts

### Execution Layers

The system operates across several distinct layers:

1. **AiThread**: The overarching context of interaction, containing project configuration and managing the user prompt
   loop.

2. **User Prompt Loop**: An interactive cycle that returns to the user after each request completion.

3. **User Run**: A complete cycle of command queue exhaustion, potentially encompassing multiple Agent calls.

4. **Agent Call**: Individual AI agent interactions with specific prompts and tools.

## LLM-Powered Orchestration

Rather than implementing complex rule-based routing, we propose using an AI Agent (the Orchestrator) to manage execution
strategies. This Agent would have specific tools for directing requests to appropriate patterns and other Agents.

### Advantages

- Natural adaptability to request complexity
- Rich context understanding for better decision-making
- Simplified architecture through prompt-based logic
- Explicit reasoning for decisions
- Easy addition of new patterns through configuration

### Challenges

- Performance overhead from additional LLM calls
- Need for consistency in decisions
- Importance of clear fallback mechanisms
- Requirement for comprehensive monitoring

## OrchestrationContext

### Separation of Concerns

The orchestration decisions and their reasoning need to be separated from the main conversation flow. This leads to a
parallel structure:

1. **AiThread.messages**: The actual conversation and tool interactions
2. **OrchestrationContext**: Metadata about orchestration decisions and their outcomes

This separation ensures:

- Clean separation of concerns
- Independent token budget management
- Proper handling during thread summarization
- Clear lifecycle boundaries

### Lifecycle Management

The OrchestrationContext is bound to the User run lifecycle, unlike AiThread which extends before and beyond it. This
placement:

- Clarifies responsibility boundaries
- Simplifies state management
- Enables efficient context handling

### Incremental Decision Making

Initial orchestration decisions require full AiThread context, but subsequent decisions within the same User run can
operate on deltas:

- First decision: Full context assessment
- Following decisions: Focus on new messages and outcomes
- Efficient resource usage through targeted context analysis

## Learning and Improvement

The system implements two complementary learning loops:

### Immediate Learning

- Occurs at User run completion
- "Post-mortem" analysis of decision outcomes
- Captures tactical learnings about pattern effectiveness
- Immediate feedback for technical optimizations

### Delayed User Feedback

- Based on actual user experience and feedback
- Requires preserving OrchestrationContexts
- Captures strategic insights about user needs
- May use rolling window or selective archiving of contexts

## Decision Dimensions

Orchestration operates across multiple dimensions:

1. **Agent Identity**: Selection of appropriate AI agents
2. **Pattern Complexity**: Choice of execution strategy
3. **Agent Parameters**: Fine-tuning of agent behavior
4. **Team Composition**: Potential coordination of multiple agents

Rather than enforcing strict ordering of these decisions, the Orchestrator maintains explicit reasoning about its
choices across all dimensions. This reasoning:

- Enables attribution of success/failure
- Facilitates learning and improvement
- Adapts naturally to evolving AI capabilities
- Supports both structured and fluid combinations

## Future Considerations

1. **Teams of Agents**
    - Coordination of multiple specialized agents
    - Parallel or sequential execution patterns
    - Complex interaction choreography

2. **Pattern Evolution**
    - Adaptation to improving AI capabilities
    - Retirement of obsolete patterns
    - Introduction of new strategies

3. **Learning System Enhancement**
    - Integration of user feedback mechanisms
    - Refinement of learning loops
    - Optimization of context preservation

## Next Steps

1. Define the OrchestrationContext structure and integration with UserRun
2. Design the learning loops implementation
3. Develop the Orchestrator Agent's tool set
4. Create monitoring and metrics system

The orchestration system represents a key evolution in Coday's architecture, providing flexible, intelligent management
of execution strategies while maintaining clear structure and opportunities for continuous improvement.