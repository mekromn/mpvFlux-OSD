package app.marlboroadvance.mpvex.ui.player.controls

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 * Shader Lab is always rendered with its own high-contrast dark-glass palette.
 * It must never inherit a dark/black accent from the app's light theme because
 * that makes interactive text disappear over video.
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
      val shaderLabColors = darkColorScheme(
        primary = Color(0xFFC9B8FF),
        onPrimary = Color(0xFF160B33),
        primaryContainer = Color(0xFF4C2E82),
        onPrimaryContainer = Color(0xFFF7F2FF),
        secondary = Color(0xFF80E9FF),
        onSecondary = Color(0xFF001F25),
        secondaryContainer = Color(0xFF17343B),
        onSecondaryContainer = Color(0xFFE5FAFF),
        tertiary = Color(0xFFFFB5E8),
        onTertiary = Color(0xFF321027),
        background = Color(0xFF0B0A0F),
        onBackground = Color(0xFFF8F5FC),
        surface = Color(0xFF121016),
        onSurface = Color(0xFFF8F5FC),
        surfaceVariant = Color(0xFF29252F),
        onSurfaceVariant = Color(0xFFDCD5E3),
        outline = Color(0xFFAAA2B3),
        outlineVariant = Color(0xFF625B69),
        error = Color(0xFFFF8A84),
        onError = Color(0xFF3A0000),
        errorContainer = Color(0xFF6B1B1B),
        onErrorContainer = Color(0xFFFFE9E7),
      )

      MaterialTheme(colorScheme = shaderLabColors) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF8F5FC)) {
          Box(Modifier.fillMaxSize()) {
            ShaderLabStudioOverlay()
            ShaderLabStatsOverlay()
          }
        }
      }
    }
  }
}
