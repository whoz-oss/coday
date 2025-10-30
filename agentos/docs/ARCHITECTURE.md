# AgentOS Architecture

## System Overview

AgentOS is designed as a lightweight, extensible framework for managing and orchestrating AI agents in a context-aware manner.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                         Client Layer                         │
│  (HTTP Clients, Web UI, CLI, Other Services)                │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ REST API (JSON)
                         │
┌────────────────────────▼────────────────────────────────────┐
│                    API Layer                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │         AgentController                              │   │
│  │  - GET /api/agents                                   │   │
│  │  - POST /api/agents/query                           │   │
│  │  - POST /api/agents                                  │   │
│  │  - PUT /api/agents/{id}                             │   │
│  │  - DELETE /api/agents/{id}                          │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ Service Calls
                         │
┌────────────────────────▼────────────────────────────────────┐
│                   Service Layer                              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │         AgentRegistry                                │   │
│  │  - registerAgent()                                   │   │
│  │  - findAgents(context)                              │   │
│  │  - getAgent(id)                                      │   │
│  │  - updateAgent()                                     │   │
│  │  - unregisterAgent()                                 │   │
│  │                                                       │   │
│  │  Filtering Logic:                                    │   │
│  │  1. Status filtering                                 │   │
│  │  2. Context type matching                            │   │
│  │  3. Capability matching                              │   │
│  │  4. Tag matching                                     │   │
│  │  5. Priority filtering                               │   │
│  │  6. Sorting by priority                              │   │
│  │  7. Result limiting                                  │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ Domain Objects
                         │
┌────────────────────────▼────────────────────────────────────┐
│                    Domain Layer                              │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │   Agent      │  │ AgentContext │  │ ContextType     │  │
│  │              │  │              │  │  (Enum)         │  │
│  │ - id         │  │ - contextTypes│ │ - CODE_REVIEW  │  │
│  │ - name       │  │ - tags        │ │ - TESTING      │  │
│  │ - description│  │ - capabilities│ │ - SECURITY     │  │
│  │ - version    │  │ - excludeStatuses│ - DEPLOYMENT │  │
│  │ - capabilities│ │ - minPriority │ │ - ...          │  │
│  │ - requiredContext│ - maxResults│ │                 │  │
│  │ - tags       │  └──────────────┘  └─────────────────┘  │
│  │ - priority   │                                          │
│  │ - status     │  ┌──────────────┐                       │
│  └──────────────┘  │ AgentStatus  │                       │
│                     │  (Enum)      │                       │
│                     │ - ACTIVE     │                       │
│                     │ - INACTIVE   │                       │
│                     │ - MAINTENANCE│                       │
│                     │ - DEPRECATED │                       │
│                     └──────────────┘                       │
└─────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

### API Layer (AgentController)

**Responsibilities:**
- Handle HTTP requests and responses
- Validate input data
- Transform DTOs to domain objects
- Return appropriate HTTP status codes
- Handle errors and exceptions

**Key Endpoints:**
- `GET /api/agents` - List all agents
- `POST /api/agents/query` - Query with complex filters
- `GET /api/agents/by-context` - Simple query with URL params
- `POST /api/agents` - Register new agent
- `PUT /api/agents/{id}` - Update existing agent
- `DELETE /api/agents/{id}` - Remove agent

### Service Layer (AgentRegistry)

**Responsibilities:**
- Manage agent lifecycle
- Execute business logic for agent queries
- Apply filtering and sorting rules
- Maintain in-memory agent registry
- Provide agent discovery services

**Key Operations:**
- Agent registration and deregistration
- Context-based agent discovery
- Multi-criteria filtering
- Priority-based sorting
- Result limiting

### Domain Layer

**Responsibilities:**
- Define core business entities
- Encapsulate business rules
- Provide type safety
- Define enumerations for fixed sets

**Key Entities:**
- `Agent` - Core agent representation
- `AgentContext` - Query context and filters
- `ContextType` - Domain context enumeration
- `AgentStatus` - Agent lifecycle states
- `AgentQueryResponse` - Query result wrapper

## Data Flow

### Query Flow

```
1. Client sends query request
   POST /api/agents/query
   {
     "contextTypes": ["CODE_REVIEW"],
     "minPriority": 8
   }

2. AgentController receives request
   - Validates input
   - Transforms to AgentContext

3. AgentRegistry.findAgents(context)
   - Filters by status
   - Filters by context types
   - Filters by priority
   - Sorts by priority
   - Limits results

4. Returns AgentQueryResponse
   {
     "agents": [...],
     "totalCount": 5,
     "context": {...}
   }

5. AgentController returns HTTP 200 with JSON
```

### Registration Flow

```
1. Client sends registration request
   POST /api/agents
   {
     "id": "new-agent",
     "name": "New Agent",
     ...
   }

2. AgentController validates input

3. AgentRegistry.registerAgent(agent)
   - Stores in registry map
   - Returns registered agent

4. Controller returns HTTP 201 with agent data
```

## Design Patterns

### Repository Pattern (In-Memory)
- `AgentRegistry` acts as an in-memory repository
- Encapsulates data access logic
- Can be easily replaced with database implementation

### Data Transfer Object (DTO)
- `AgentQueryRequest` - API request DTO
- Separates API contracts from domain models

### Builder Pattern
- `AgentContext.forContextType()` - Convenient builders
- `AgentContext.empty()` - Default context

### Strategy Pattern (Implicit)
- Filtering logic can be extended with different strategies
- Each filter is a separate concern

## Extension Points

### 1. Persistence Layer

```kotlin
interface AgentRepository {
    fun save(agent: Agent): Agent
    fun findById(id: String): Agent?
    fun findAll(): List<Agent>
    fun delete(id: String): Boolean
}

@Repository
class JpaAgentRepository : AgentRepository {
    // Implementation using JPA
}
```

### 2. Agent Execution

```kotlin
interface AgentExecutor {
    fun execute(agentId: String, input: Any): AgentExecutionResult
}

@Service
class DefaultAgentExecutor : AgentExecutor {
    // Execution logic
}
```

### 3. Agent Discovery Extensions

```kotlin
interface AgentDiscoveryStrategy {
    fun discover(context: AgentContext): List<Agent>
}

class MLBasedDiscovery : AgentDiscoveryStrategy {
    // ML-based agent recommendation
}
```

### 4. Agent Composition

```kotlin
interface AgentComposer {
    fun compose(agents: List<Agent>): CompositeAgent
}
```

## Security Considerations

### Current State
- No authentication/authorization (development phase)
- All endpoints are public

### Future Enhancements
- Spring Security integration
- JWT-based authentication
- Role-based access control (RBAC)
- API key management
- Rate limiting

```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/agents").authenticated()
                    .requestMatchers("/actuator/health").permitAll()
            }
            .oauth2ResourceServer { it.jwt() }
        return http.build()
    }
}
```

## Performance Considerations

### Current Implementation
- In-memory storage (fast, but not persistent)
- Linear filtering (O(n) for each filter)
- No caching

### Optimization Strategies

1. **Indexing**
   ```kotlin
   // Index by context type
   private val contextTypeIndex: Map<ContextType, Set<String>>
   
   // Index by capability
   private val capabilityIndex: Map<String, Set<String>>
   ```

2. **Caching**
   ```kotlin
   @Cacheable("agents")
   fun findAgents(context: AgentContext): AgentQueryResponse
   ```

3. **Database Implementation**
   ```sql
   CREATE INDEX idx_agent_context ON agents(context_type);
   CREATE INDEX idx_agent_capability ON agent_capabilities(capability);
   CREATE INDEX idx_agent_priority ON agents(priority DESC);
   ```

## Monitoring and Observability

### Metrics to Track
- Agent query performance
- Most frequently queried contexts
- Agent usage statistics
- Query result sizes
- Error rates

### Implementation
```kotlin
@Service
class MonitoredAgentRegistry(
    private val meterRegistry: MeterRegistry
) : AgentRegistry {
    
    override fun findAgents(context: AgentContext): AgentQueryResponse {
        val timer = Timer.start(meterRegistry)
        try {
            val result = super.findAgents(context)
            meterRegistry.counter("agents.queries.success").increment()
            return result
        } finally {
            timer.stop(Timer.sample(meterRegistry))
        }
    }
}
```

## Testing Strategy

### Unit Tests
- Test each filter independently
- Test sorting logic
- Test edge cases (empty results, no filters)

### Integration Tests
- Test full API endpoints
- Test with real HTTP requests
- Test error handling

### Performance Tests
- Load testing with many agents
- Query performance benchmarks
- Concurrent access testing

## Deployment Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Load Balancer                         │
└────────────┬────────────────────────────────────────────┘
             │
             ├────────────┬────────────┬────────────┐
             │            │            │            │
        ┌────▼───┐   ┌───▼────┐  ┌───▼────┐  ┌───▼────┐
        │ AgentOS│   │ AgentOS│  │ AgentOS│  │ AgentOS│
        │Instance│   │Instance│  │Instance│  │Instance│
        └────┬───┘   └────┬───┘  └────┬───┘  └────┬───┘
             │            │            │            │
             └────────────┴────────────┴────────────┘
                          │
                          │
                    ┌─────▼──────┐
                    │  Database  │
                    │  (Future)  │
                    └────────────┘
```

## Future Architecture Enhancements

1. **Event-Driven Architecture**
   - Publish agent registration events
   - Subscribe to agent status changes
   - Enable reactive updates

2. **Microservices Split**
   - Agent Registry Service
   - Agent Execution Service
   - Agent Monitoring Service

3. **API Gateway**
   - Centralized authentication
   - Rate limiting
   - Request routing

4. **Service Mesh**
   - Service discovery
   - Load balancing
   - Circuit breaking
