package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event-driven copy of the state published by the bundled Shader Lab controller.
 * libmpv already emits this property through PlayerObserver, so Compose can
 * consume it without repeatedly crossing JNI on a polling timer.
 */
object ShaderLabStateBus {
  const val PROPERTY = "user-data/p9lab/native-state"

  private val _state = MutableStateFlow<Map<String, String>>(emptyMap())
  val state: StateFlow<Map<String, String>> = _state.asStateFlow()

  fun update(raw: String) {
    _state.value = parse(raw)
  }

  fun clear() {
    _state.value = emptyMap()
  }

  private fun parse(raw: String): Map<String, String> =
    raw.lineSequence()
      .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
      }
      .toMap()
}
