package app.marlboroadvance.mpvex.ui.player.controls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared visibility state for the production Shader Lab Studio.
 *
 * Player controls and the XML-hosted Shader Lab ComposeView live in separate
 * Compose trees, so the open/close state must not be local remember state.
 */
class ShaderLabUiController {
  private val _visible = MutableStateFlow(false)
  val visible: StateFlow<Boolean> = _visible.asStateFlow()

  fun open() {
    _visible.value = true
  }

  fun close() {
    _visible.value = false
  }

  fun toggle() {
    _visible.value = !_visible.value
  }
}
