package app.marlboroadvance.mpvex.ui.player.controls

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Full-player host for the R08 Shader Lab Studio.
 *
 * The Studio needs the complete player bounds to choose a wide landscape
 * workstation or compact portrait layout. A full-size Android sibling must not
 * intercept player gestures while the Studio is closed, so the host is kept
 * GONE at the Android View level until ShaderLabUiController says it is open.
 * While open the Studio is intentionally modal; Close restores the normal
 * player/control touch path immediately.
 */
class ShaderLabR08OverlayView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {
  private var visibilityScope: CoroutineScope? = null
  private var visibilityJob: Job? = null

  private val uiController: ShaderLabUiController by lazy {
    GlobalContext.get().get()
  }

  init {
    visibility = View.GONE
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    visibilityScope?.cancel()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    visibilityScope = scope
    visibilityJob = scope.launch {
      uiController.visible.collectLatest { open ->
        visibility = if (open) View.VISIBLE else View.GONE
      }
    }
  }

  override fun onDetachedFromWindow() {
    visibilityJob?.cancel()
    visibilityJob = null
    visibilityScope?.cancel()
    visibilityScope = null
    super.onDetachedFromWindow()
  }

  @Composable
  override fun Content() {
    MpvexTheme {
      ShaderLabStudioOverlay()
    }
  }
}
