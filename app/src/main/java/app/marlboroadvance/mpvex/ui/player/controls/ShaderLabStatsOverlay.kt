package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.ShaderLabBackendState
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.ShaderLabResidentGpuTransport
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * Optional R08 "Stats for Nerds" overlay.
 *
 * This intentionally lives outside the Studio drawer so it can remain visible
 * over the video while a shader control is being adjusted. The compact view is
 * renderer-first; EXPAND exposes the transport and frame-timing details that
 * are useful when validating the R08 resident PARAM path on a real device.
 */
@Composable
fun ShaderLabStatsOverlay(
  modifier: Modifier = Modifier,
) {
  val bridge = koinInject<MpvShaderLabBridge>()
  val uiController = koinInject<ShaderLabUiController>()
  val backend by bridge.state.collectAsState()
  val studioVisible by uiController.visible.collectAsState()
  val clipboard = LocalClipboardManager.current

  var open by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }
  var stats by remember { mutableStateOf(ShaderLabRuntimeStats.EMPTY) }

  LaunchedEffect(studioVisible, open) {
    if (!studioVisible) {
      open = false
      expanded = false
      return@LaunchedEffect
    }
    while (studioVisible) {
      stats = readShaderLabRuntimeStats()
      delay(if (open) 350L else 1000L)
    }
  }

  if (!studioVisible) return

  Box(modifier = modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(start = 12.dp, top = 12.dp)
        .widthIn(max = 430.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(onClick = { open = !open }) {
        Text(if (open) "STATS ×" else "STATS")
      }

      if (open) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
          tonalElevation = 3.dp,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                "CHROVELO • STATS FOR NERDS",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
              )
              TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "LESS" else "MORE")
              }
              TextButton(onClick = { clipboard.setText(AnnotatedString(stats.asDiagnosticText(backend))) }) {
                Text("COPY")
              }
            }

            StatRow("Renderer", stats.activeVo ?: "—", highlight = true)
            if (stats.requestedVo != null && stats.requestedVo != stats.activeVo) {
              StatRow("Requested VO", stats.requestedVo)
            }
            StatRow("GPU", listOfNotNull(stats.gpuApi, stats.gpuContext).joinToString(" • ").ifBlank { "—" })
            StatRow("HW decode", stats.hwdecCurrent ?: "—")
            StatRow("Source", stats.sourceLine(backend))
            StatRow("Video", stats.videoLine())
            StatRow("Resident shader", if (stats.residentAttached) "ATTACHED" else "NOT ATTACHED", highlight = stats.residentAttached)
            StatRow("R08 PARAM", stats.paramStatusLine(), highlight = stats.paramHealthy)
            StatRow("Drops", stats.dropLine())

            if (expanded) {
              Spacer(Modifier.height(2.dp))
              StatRow("FBO", stats.fboFormat ?: "—")
              StatRow("Pixel format", stats.pixelFormat ?: stats.videoFormat ?: "—")
              StatRow("Color", listOfNotNull(stats.primaries, stats.matrix, stats.sourceGamma).joinToString(" • ").ifBlank { "—" })
              StatRow("FPS", stats.fpsLine())
              StatRow("PARAM count", stats.paramCount?.toString() ?: "—")
              StatRow("PARAM publishes", stats.publishCount ?: "—")
              StatRow("Read-back", stats.readbackMatches ?: "—")
              StatRow("Shader-list mutations", stats.shaderListMutations ?: "—")
              StatRow("Command latency", stats.commandLatency ?: "—")
              StatRow("Mistimed", stats.mistimed ?: "—")
              StatRow("VO delayed", stats.voDelayed ?: "—")
              StatRow("Bridge", when {
                backend.ready -> "LIVE"
                backend.connected -> "SYNC"
                else -> "OFFLINE"
              })
              backend.lastError?.let { StatRow("Last error", it) }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StatRow(
  label: String,
  value: String,
  highlight: Boolean = false,
) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      label,
      modifier = Modifier.widthIn(min = 104.dp, max = 132.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontFamily = FontFamily.Monospace,
    )
    Text(
      value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelSmall,
      color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      fontFamily = FontFamily.Monospace,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private data class ShaderLabRuntimeStats(
  val activeVo: String?,
  val requestedVo: String?,
  val gpuApi: String?,
  val gpuContext: String?,
  val fboFormat: String?,
  val hwdecCurrent: String?,
  val width: String?,
  val height: String?,
  val videoCodec: String?,
  val videoFormat: String?,
  val pixelFormat: String?,
  val sourceGamma: String?,
  val primaries: String?,
  val matrix: String?,
  val containerFps: String?,
  val estimatedFps: String?,
  val displayFps: String?,
  val frameDrop: String?,
  val decoderDrop: String?,
  val mistimed: String?,
  val voDelayed: String?,
  val residentAttached: Boolean,
  val paramCount: Int?,
  val proofStatus: String?,
  val publishCount: String?,
  val readbackMatches: String?,
  val shaderListMutations: String?,
  val commandLatency: String?,
) {
  val paramHealthy: Boolean
    get() = proofStatus == "PASS" || readbackMatches == "true"

  fun sourceLine(backend: ShaderLabBackendState): String =
    buildString {
      append(backend.sourceKind.name.replace('_', '-'))
      val gamma = sourceGamma ?: backend.sourceGamma
      if (!gamma.isNullOrBlank()) append(" • $gamma")
    }

  fun videoLine(): String =
    buildString {
      if (!width.isNullOrBlank() && !height.isNullOrBlank()) append("${width}×${height}")
      if (!videoCodec.isNullOrBlank()) {
        if (isNotEmpty()) append(" • ")
        append(videoCodec)
      }
      if (isEmpty()) append(videoFormat ?: "—")
    }

  fun fpsLine(): String =
    listOfNotNull(
      containerFps?.let { "src $it" },
      estimatedFps?.let { "vf $it" },
      displayFps?.let { "display $it" },
    ).joinToString(" • ").ifBlank { "—" }

  fun dropLine(): String =
    listOfNotNull(
      frameDrop?.let { "vo $it" },
      decoderDrop?.let { "dec $it" },
    ).joinToString(" • ").ifBlank { "—" }

  fun paramStatusLine(): String =
    when {
      proofStatus != null -> proofStatus
      residentAttached && paramCount != null -> "${paramCount} opts • awaiting proof"
      residentAttached -> "ATTACHED • awaiting proof"
      else -> "OFFLINE"
    }

  fun asDiagnosticText(backend: ShaderLabBackendState): String = buildString {
    appendLine("Chrovelo Shader Lab runtime snapshot")
    appendLine("renderer_active=${activeVo.orEmpty()}")
    appendLine("renderer_requested=${requestedVo.orEmpty()}")
    appendLine("gpu_api=${gpuApi.orEmpty()}")
    appendLine("gpu_context=${gpuContext.orEmpty()}")
    appendLine("fbo_format=${fboFormat.orEmpty()}")
    appendLine("hwdec_current=${hwdecCurrent.orEmpty()}")
    appendLine("source_kind=${backend.sourceKind}")
    appendLine("source_gamma=${(sourceGamma ?: backend.sourceGamma).orEmpty()}")
    appendLine("video=${videoLine()}")
    appendLine("fps=${fpsLine()}")
    appendLine("resident_shader_attached=$residentAttached")
    appendLine("param_count=${paramCount ?: -1}")
    appendLine("r08_proof_status=${proofStatus.orEmpty()}")
    appendLine("parameter_publish_count=${publishCount.orEmpty()}")
    appendLine("readback_matches_requested=${readbackMatches.orEmpty()}")
    appendLine("shader_list_mutations=${shaderListMutations.orEmpty()}")
    appendLine("command_latency=${commandLatency.orEmpty()}")
    appendLine("frame_drop=${frameDrop.orEmpty()}")
    appendLine("decoder_drop=${decoderDrop.orEmpty()}")
    appendLine("mistimed=${mistimed.orEmpty()}")
    appendLine("vo_delayed=${voDelayed.orEmpty()}")
    appendLine("bridge_connected=${backend.connected}")
    appendLine("bridge_ready=${backend.ready}")
    appendLine("backend_version=${backend.backendVersion.orEmpty()}")
    appendLine("last_error=${backend.lastError.orEmpty()}")
  }

  companion object {
    val EMPTY = ShaderLabRuntimeStats(
      activeVo = null,
      requestedVo = null,
      gpuApi = null,
      gpuContext = null,
      fboFormat = null,
      hwdecCurrent = null,
      width = null,
      height = null,
      videoCodec = null,
      videoFormat = null,
      pixelFormat = null,
      sourceGamma = null,
      primaries = null,
      matrix = null,
      containerFps = null,
      estimatedFps = null,
      displayFps = null,
      frameDrop = null,
      decoderDrop = null,
      mistimed = null,
      voDelayed = null,
      residentAttached = false,
      paramCount = null,
      proofStatus = null,
      publishCount = null,
      readbackMatches = null,
      shaderListMutations = null,
      commandLatency = null,
    )
  }
}

private fun readShaderLabRuntimeStats(): ShaderLabRuntimeStats {
  fun s(vararg names: String): String? {
    names.forEach { name ->
      val value = runCatching { MPVLib.getPropertyString(name) }.getOrNull()?.trim()
      if (!value.isNullOrBlank()) return value
    }
    return null
  }

  fun number(vararg names: String): String? {
    names.forEach { name ->
      val direct = runCatching { MPVLib.getPropertyDouble(name) }.getOrNull()
      if (direct != null && direct.isFinite()) return formatNumber(direct)
      val text = s(name)?.toDoubleOrNull()
      if (text != null && text.isFinite()) return formatNumber(text)
    }
    return null
  }

  val shaders = s("options/glsl-shaders", "glsl-shaders")
  val opts = s("options/glsl-shader-opts", "glsl-shader-opts")
  val proof = readResidentProof()

  return ShaderLabRuntimeStats(
    activeVo = s("current-vo", "vo"),
    requestedVo = s("options/vo"),
    gpuApi = s("options/gpu-api", "gpu-api"),
    gpuContext = s("options/gpu-context", "gpu-context"),
    fboFormat = s("options/fbo-format", "fbo-format"),
    hwdecCurrent = s("hwdec-current"),
    width = number("width", "video-params/w"),
    height = number("height", "video-params/h"),
    videoCodec = s("video-codec"),
    videoFormat = s("video-format"),
    pixelFormat = s("video-params/pixelformat"),
    sourceGamma = s("video-params/gamma"),
    primaries = s("video-params/primaries"),
    matrix = s("video-params/colormatrix"),
    containerFps = number("container-fps"),
    estimatedFps = number("estimated-vf-fps"),
    displayFps = number("display-fps"),
    frameDrop = number("frame-drop-count"),
    decoderDrop = number("decoder-frame-drop-count"),
    mistimed = number("mistimed-frame-count"),
    voDelayed = number("vo-delayed-frame-count"),
    residentAttached = shaders?.contains(ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH) == true,
    paramCount = opts?.split(',')?.count { token -> token.contains('=') },
    proofStatus = proof["status"],
    publishCount = proof["parameter_publish_count"],
    readbackMatches = proof["readback_matches_requested"],
    shaderListMutations = proof["burst_shader_list_mutations"],
    commandLatency = proof["last_command_latency_us"]?.let { "$it µs" },
  )
}

private fun readResidentProof(): Map<String, String> =
  runCatching {
    File("/storage/emulated/0/mpv/logs/shaderlab-r08-resident-proof.txt")
      .takeIf(File::isFile)
      ?.readLines()
      ?.mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
      }
      ?.toMap()
      .orEmpty()
  }.getOrDefault(emptyMap())

private fun formatNumber(value: Double): String {
  val rounded = value.toLong()
  return if (kotlin.math.abs(value - rounded) < 0.000001) {
    rounded.toString()
  } else {
    String.format(Locale.US, "%.3f", value)
  }
}
