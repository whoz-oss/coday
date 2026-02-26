// agentos-dataflow public API
//
// Typed data flow from the AgentOS API to the UI layer:
// namespace and case state, event streams, derived observables.
//
// Depends on @whoz-oss/agentos-api-client for generated types.
// No Angular DI (@Injectable etc.) — that binding layer lives in agentos-ui.

export { NamespaceState } from './lib/namespace.state'
