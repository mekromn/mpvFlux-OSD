package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstallState
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller

/**
 * Production R08 transport instrumentation.
 *
 * Slider movement must stay on the shortest possible path:
 * Android value -> one mpv set command -> resident vo=gpu PARAM update.
 *
 * The original proof wrapper intentionally read back renderer/frame properties
 * after every publish. That is useful in a unit test but is hostile to a live
 * grading surface because several synchronous JNI/mpv property reads happen on
 * every pointer tick. This wrapper records only the command latency while the
 * gesture is active and debounces the expensive proof snapshot until input has
 * been idle for [settleMillis].
 */
internal class ShaderLabR08DebouncedProbedMpvTransport(
  private val delegate: ShaderLabMpvTransport,
  private val probe: ShaderLabR08ResidentProbe = NoOpShaderLabR08ResidentProbe,
  private val handler: Handler = Handler(Looper.getMainLooper()),
  private val settleMillis: Long = 180L,
  private val nanoTime: () -> Long = System::nanoTime,
) : ShaderLabMpvTransport {
  private data class PendingPublish(
    val requestedOptions: String,
    val commandLatencyNanos: Long,
  )

  private var pendingPublish: PendingPublish? = null

  private val settleRunnable = Runnable { captureSettledSnapshot() }

  override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) {
    delegate.attach(listener)
    runCatching { probe.attach() }
  }

  override fun detach() {
    handler.removeCallbacks(settleRunnable)
    pendingPublish = null
    runCatching { probe.detach() }
    delegate.detach()
  }

  override fun observeString(property: String) = delegate.observeString(property)

  override fun observeDouble(property: String) = delegate.observeDouble(property)

  override fun getString(property: String): String? = delegate.getString(property)

  override fun getDouble(property: String): Double? = delegate.getDouble(property)

  override fun command(vararg args: String) {
    val isShaderListMutation =
      args.getOrNull(0) == "change-list" && args.getOrNull(1) == "glsl-shaders"
    if (isShaderListMutation) runCatching { probe.shaderListMutation() }

    val isResidentPublish =
      args.getOrNull(0) == "set" &&
        args.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY &&
        args.size >= 3

    if (!isResidentPublish) {
      delegate.command(*args)
      return
    }

    val commandStart = nanoTime()
    delegate.command(*args)
    val commandEnd = nanoTime()

    pendingPublish =
      PendingPublish(
        requestedOptions = args[2],
        commandLatencyNanos = (commandEnd - commandStart).coerceAtLeast(0L),
      )

    // No property reads here. Keep the drag hot path to one mpv command.
    handler.removeCallbacks(settleRunnable)
    handler.postDelayed(settleRunnable, settleMillis)
  }

  private fun captureSettledSnapshot() {
    val pending = pendingPublish ?: return
    pendingPublish = null

    val sampleStart = nanoTime()
    val sample =
      ShaderLabR08ResidentPublishSample(
        requestedOptions = pending.requestedOptions,
        readbackOptions = safeString(ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY),
        commandLatencyNanos = pending.commandLatencyNanos,
        // In this production wrapper this measures the settled diagnostic
        // snapshot itself, not time spent blocking the active slider command.
        setAndReadbackLatencyNanos = 0L,
        sourceGamma = safeString(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY),
        shaderList = safeString("glsl-shaders"),
        frameDropCount = safeLong("frame-drop-count"),
        decoderFrameDropCount = safeLong("decoder-frame-drop-count"),
        mistimedFrameCount = safeLong("mistimed-frame-count"),
        voDelayedFrameCount = safeLong("vo-delayed-frame-count"),
        videoOutput = safeString("vo"),
        gpuApi = safeString("gpu-api"),
        gpuContext = safeString("gpu-context"),
        hwdecCurrent = safeString("hwdec-current"),
      )

    val finished = nanoTime()
    runCatching {
      probe.residentPublish(
        sample.copy(setAndReadbackLatencyNanos = (finished - sampleStart).coerceAtLeast(0L)),
      )
    }
  }

  private fun safeString(property: String): String? =
    runCatching { delegate.getString(property) }.getOrNull()

  private fun safeLong(property: String): Long? =
    runCatching {
      delegate.getString(property)?.trim()?.toDoubleOrNull()?.toLong()
        ?: delegate.getDouble(property)?.toLong()
    }.getOrNull()
}

internal fun createR08LowOverheadMpvShaderLabBridge(
  context: Context,
  engineInstaller: ShaderLabEngineInstaller,
): MpvShaderLabBridge =
  MpvShaderLabBridge(
    transport =
      ShaderLabR08DebouncedProbedMpvTransport(
        delegate = LibMpvShaderLabTransport(),
        probe = FileShaderLabR08ResidentProbe(context),
      ),
    syncProbe = FileShaderLabBridgeSyncProbe(),
    prepareEngine = {
      when (val installState = engineInstaller.installOrRepair()) {
        is ShaderLabEngineInstallState.Success -> Unit
        is ShaderLabEngineInstallState.Blocked ->
          error("Shader Lab workspace unavailable: ${installState.workspaceState}")
        is ShaderLabEngineInstallState.Failure -> error(installState.reason)
        ShaderLabEngineInstallState.Idle -> error("Shader Lab engine installer remained idle")
      }
    },
    schedule = { delayMillis, task ->
      Handler(Looper.getMainLooper()).postDelayed(task, delayMillis)
    },
  )
