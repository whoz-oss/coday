package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * Fail-safe lifecycle observer notified of fragment and parent Run transitions
 * (e.g. for Jira or external status synchronization). Exceptions thrown by an
 * observer are logged and swallowed by the host.
 */
interface FactoryRunLifecycleObserver : ExtensionPoint {
    fun onFragmentStarted(run: RunView, fragment: FragmentView) {}

    fun onFragmentFinished(run: RunView, outcome: FragmentOutcome) {}

    fun onRunFinished(run: RunView, status: String) {}
}
