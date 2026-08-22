package app.marlboroadvance.mpvex.ui.player.controls

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme

/**
 * Compatibility host for the R08 Lab entry point in player_layout.xml.
 *
 * The old imperative FrameLayout/button harness has been removed. Shader Lab
 * is now rendered by native Compose controls and Canvas curves, while keeping
 * the proven view attachment point isolated from MPVView/rotation lifecycle.
 */
class ShaderLabR08OverlayView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {
  @Composable
  override fun Content() {
    MpvexTheme {
      ShaderLabStudioOverlay()
    }
  }
}
