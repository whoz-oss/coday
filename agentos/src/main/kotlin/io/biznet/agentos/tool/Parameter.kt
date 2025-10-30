package io.biznet.agentos.tool

interface Parameter

sealed interface ParameterResult<out T : Parameter> {
  // Tool ran successfully, provides response, loop continues
  data class Success<out T : Parameter>(
    val response: T,
  ) : ParameterResult<T>

  // Tool failed (parsing or execution), provides error message, loop continues (feeding error back)
  data class Error(
    val message: String,
  ) : ParameterResult<Nothing>
}
