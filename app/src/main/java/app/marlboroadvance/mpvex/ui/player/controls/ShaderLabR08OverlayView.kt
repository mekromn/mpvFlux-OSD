package app.marlboroadvance.mpvex.ui.player.controls

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
      // Shader Lab is intentionally a dark translucent surface over live video.
      // If the rest of the app is using its light theme, inheriting that scheme
      // directly produces black onSurface/onSurfaceVariant text over the glass
      // panel. Keep the app's accent identity while giving this video overlay a
      // stable high-contrast dark content palette in every app appearance mode.
      val appColors = MaterialTheme.colorScheme
      val shaderLabColors = darkColorScheme(
        primary = appColors.primary,
        onPrimary = Color.White,
        primaryContainer = appColors.primary.copy(alpha = 0.30f),
        onPrimaryContainer = Color.White,
        secondary = appColors.secondary,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF29242F),
        onSecondaryContainer = Color.White,
        tertiary = appColors.tertiary,
        onTertiary = Color.White,
        background = Color(0xFF0E0D12),
        onBackground = Color(0xFFF7F4FA),
        surface = Color(0xFF141218),
        onSurface = Color(0xFFF7F4FA),
        surfaceVariant = Color(0xFF25212B),
        onSurfaceVariant = Color(0xFFD1CBD7),
        outline = Color(0xFF938C9C),
        error = Color(0xFFFF716C),
        onError = Color.White,
        errorContainer = Color(0xFF6B1B1B),
        onErrorContainer = Color(0xFFFFE9E7),
      )

      MaterialTheme(colorScheme = shaderLabColors) {
        Box(Modifier.fillMaxSize()) {
          ShaderLabStudioOverlay()
          ShaderLabStatsOverlay()
        }
      }
    }
  }
}
