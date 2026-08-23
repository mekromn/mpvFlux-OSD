package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.ShaderLabResidentGpuTransport
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class ShaderLabHudSnapshot(
  val vo: String = "—",
  val gpu: String = "—",
  val context: String = "—",
  val shaderAttached: Boolean = false,
  val paramCount: Int = 0,
  val glslOptsPreview: String = "—",
  val frameDrops: String = "—",
  val decoderDrops: String = "—",
)

/**
 * Device-facing R08 Stats for Nerds HUD.
 *
 * Stats are deliberately opt-in. Reading the diagnostic mpv properties is
 * useful during R08 validation, but opening Shader Lab itself must remain a
 * zero-JNI, zero-renderer-mutation UI operation.
 */
@Composable
fun ShaderLabStatsHud(
  modifier: Modifier = Modifier,
) {
  val bridge = koinInject<MpvShaderLabBridge>()
  val uiController = koinInject<ShaderLabUiController>()
  val studioVisible by uiController.visible.collectAsState()
  val backend by bridge.state.collectAsState()

  var open by remember { mutableStateOf(false) }
  var snapshot by remember { mutableStateOf(ShaderLabHudSnapshot()) }

  LaunchedEffect(studioVisible, open) {
    if (!studioVisible || !open) return@LaunchedEffect
    while (studioVisible && open) {
      // MPVLib property access is synchronous JNI. Keep diagnostic polling off
      // Compose's main dispatcher so it cannot block touch, layout, animation,
      // or the first Shader Lab frame.
      snapshot = withContext(Dispatchers.IO) { readHudSnapshot() }
      delay(750L)
    }
  }

  if (!studioVisible) return

  Box(modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(start = 12.dp, top = 12.dp)
        .widthIn(max = 430.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
        onClick = { open = !open },
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
      ) {
        Text(
          if (open) "HIDE STATS" else "SHOW STATS",
          fontWeight = FontWeight.Bold,
        )
      }

      if (open) {
        Surface(
          color = Color(0xED0C0B10),
          contentColor = Color(0xFFF8F5FC),
          shape = RoundedCornerShape(18.dp),
          tonalElevation = 4.dp,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Text(
              "CHROVELO • STATS FOR NERDS",
              color = Color.White,
              fontWeight = FontWeight.Black,
              style = MaterialTheme.typography.labelLarge,
            )
            HudRow("Bridge", if (backend.ready) "LIVE" else if (backend.connected) "SYNC" else "OFFLINE")
            HudRow("Source", "${backend.sourceKind} • ${backend.sourceGamma ?: "—"}")
            HudRow("VO", snapshot.vo)
            HudRow("GPU", "${snapshot.gpu} • ${snapshot.context}")
            HudRow("Resident", if (snapshot.shaderAttached) "ATTACHED" else "NOT ATTACHED")
            HudRow("PARAM opts", snapshot.paramCount.toString())
            HudRow("Drops", "vo ${snapshot.frameDrops} • dec ${snapshot.decoderDrops}")
            HudRow("Opts", snapshot.glslOptsPreview)
            backend.lastError?.let { HudRow("ERROR", it) }
          }
        }
      }
    }
  }
}

@Composable
private fun HudRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      label,
      modifier = Modifier.widthIn(min = 82.dp, max = 100.dp),
      color = Color(0xFFC9B8FF),
      fontFamily = FontFamily.Monospace,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
    )
    Text(
      value,
      modifier = Modifier.weight(1f),
      color = Color.White,
      fontFamily = FontFamily.Monospace,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 2,
    )
  }
}

private fun readHudSnapshot(): ShaderLabHudSnapshot {
  fun text(vararg names: String): String? {
    names.forEach { name ->
      val value = runCatching { MPVLib.getPropertyString(name) }.getOrNull()?.trim()
      if (!value.isNullOrBlank()) return value
    }
    return null
  }

  fun number(vararg names: String): String? {
    names.forEach { name ->
      val direct = runCatching { MPVLib.getPropertyDouble(name) }.getOrNull()
      if (direct != null && direct.isFinite()) return direct.toLong().toString()
      text(name)?.toDoubleOrNull()?.let { if (it.isFinite()) return it.toLong().toString() }
    }
    return null
  }

  val shaders = text("glsl-shaders", "options/glsl-shaders").orEmpty()
  val opts = text("glsl-shader-opts", "options/glsl-shader-opts").orEmpty()
  val preview = if (opts.length <= 145) opts else opts.take(142) + "…"

  return ShaderLabHudSnapshot(
    vo = text("current-vo", "vo") ?: "—",
    gpu = text("gpu-api", "options/gpu-api") ?: "—",
    context = text("gpu-context", "options/gpu-context") ?: "—",
    shaderAttached = shaders.contains(ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
    paramCount = opts.split(',').count { it.contains('=') },
    glslOptsPreview = preview.ifBlank { "—" },
    frameDrops = number("frame-drop-count") ?: "—",
    decoderDrops = number("decoder-frame-drop-count") ?: "—",
  )
}
