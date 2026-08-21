package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstallState
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class ShaderLabR08ResidentPublishSample(
  val requestedOptions: String,
  val readbackOptions: String?,
  val commandLatencyNanos: Long,
  val setAndReadbackLatencyNanos: Long,
  val sourceGamma: String?,
  val shaderList: String?,
  val frameDropCount: Long?,
  val decoderFrameDropCount: Long?,
  val mistimedFrameCount: Long?,
  val voDelayedFrameCount: Long?,
)

internal interface ShaderLabR08ResidentProbe {
  fun attach() = Unit

  fun shaderListMutation() = Unit

  fun residentPublish(sample: ShaderLabR08ResidentPublishSample) = Unit

  fun detach() = Unit
}

internal data object NoOpShaderLabR08ResidentProbe : ShaderLabR08ResidentProbe

/**
 * R08 device-proof transport wrapper.
 *
 * It does not alter commands. Only resident glsl-shader-opts writes are timed
 * and read back. File hashing/log I/O is debounced until input has been idle,
 * so ordinary pointer-event tuning remains free of file I/O.
 */
internal class ShaderLabR08ProbedMpvTransport(
  private val delegate: ShaderLabMpvTransport,
  private val probe: ShaderLabR08ResidentProbe = NoOpShaderLabR08ResidentProbe,
  private val nanoTime: () -> Long = System::nanoTime,
) : ShaderLabMpvTransport {
  override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) {
    delegate.attach(listener)
    runCatching { probe.attach() }
  }

  override fun detach() {
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

    val requested = args[2]
    val commandStart = nanoTime()
    delegate.command(*args)
    val commandEnd = nanoTime()

    val readback = safeString(ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY)
    val sample =
      ShaderLabR08ResidentPublishSample(
        requestedOptions = requested,
        readbackOptions = readback,
        commandLatencyNanos = (commandEnd - commandStart).coerceAtLeast(0L),
        setAndReadbackLatencyNanos = (nanoTime() - commandStart).coerceAtLeast(0L),
        sourceGamma = safeString(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY),
        shaderList = safeString("glsl-shaders"),
        frameDropCount = safeLong("frame-drop-count"),
        decoderFrameDropCount = safeLong("decoder-frame-drop-count"),
        mistimedFrameCount = safeLong("mistimed-frame-count"),
        voDelayedFrameCount = safeLong("vo-delayed-frame-count"),
      )
    runCatching { probe.residentPublish(sample) }
  }

  private fun safeString(property: String): String? =
    runCatching { delegate.getString(property) }.getOrNull()

  private fun safeLong(property: String): Long? =
    runCatching {
      delegate.getString(property)?.trim()?.toDoubleOrNull()?.toLong()
        ?: delegate.getDouble(property)?.toLong()
    }.getOrNull()
}

internal class FileShaderLabR08ResidentProbe(
  context: Context,
  private val file: File = File("/storage/emulated/0/mpv/logs/shaderlab-r08-resident-proof.txt"),
  private val residentShader: File = File(ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
  private val idleMillis: Long = 300L,
) : ShaderLabR08ResidentProbe {
  private data class FileSnapshot(
    val exists: Boolean,
    val bytes: Long,
    val modifiedMillis: Long,
    val sha256: String?,
  )

  private val handler = Handler(Looper.getMainLooper())
  private val powerManager = context.getSystemService(PowerManager::class.java)
  private var baselineShader: FileSnapshot? = null
  private var lastSample: ShaderLabR08ResidentPublishSample? = null
  private var burstStartSample: ShaderLabR08ResidentPublishSample? = null
  private var burstActive = false
  private var burstUpdateCount = 0L
  private var burstShaderListMutations = 0L
  private var totalUpdateCount = 0L
  private var totalCommandLatencyNanos = 0L
  private var maxCommandLatencyNanos = 0L
  private var burstStartThermal: Int? = null

  private val idleWrite = Runnable { writeIdleProof() }

  @Synchronized
  override fun attach() {
    baselineShader = captureFileSnapshot(residentShader)
    burstActive = false
    burstUpdateCount = 0L
    burstShaderListMutations = 0L
    handler.removeCallbacks(idleWrite)
  }

  @Synchronized
  override fun shaderListMutation() {
    if (burstActive) burstShaderListMutations += 1L
  }

  @Synchronized
  override fun residentPublish(sample: ShaderLabR08ResidentPublishSample) {
    if (!burstActive) {
      burstActive = true
      burstStartSample = sample
      burstUpdateCount = 0L
      burstShaderListMutations = 0L
      burstStartThermal = thermalStatus()
    }

    burstUpdateCount += 1L
    totalUpdateCount += 1L
    totalCommandLatencyNanos += sample.commandLatencyNanos
    maxCommandLatencyNanos = maxOf(maxCommandLatencyNanos, sample.commandLatencyNanos)
    lastSample = sample

    handler.removeCallbacks(idleWrite)
    handler.postDelayed(idleWrite, idleMillis)
  }

  @Synchronized
  override fun detach() {
    handler.removeCallbacks(idleWrite)
    if (burstActive) writeIdleProof()
  }

  @Synchronized
  private fun writeIdleProof() {
    val sample = lastSample ?: return
    val first = burstStartSample ?: sample
    val currentShader = captureFileSnapshot(residentShader)
    val baseline = baselineShader
    val shaderUnchanged = baseline != null && baseline == currentShader
    val readbackMatches = sample.readbackOptions == sample.requestedOptions
    val sourceKind = classifySource(sample.sourceGamma)
    val residentAttached =
      sample.shaderList?.contains(ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH) == true
    val enoughLiveUpdates = totalUpdateCount >= 2L
    val noShaderListMutation = burstShaderListMutations == 0L

    val status =
      when {
        !enoughLiveUpdates || sourceKind != "SDR" -> "WAITING"
        readbackMatches && residentAttached && shaderUnchanged && noShaderListMutation -> "PASS"
        else -> "FAIL"
      }

    val thermalEnd = thermalStatus()
    val avgCommandLatencyNanos =
      if (totalUpdateCount == 0L) 0L else totalCommandLatencyNanos / totalUpdateCount

    writeAtomically(
      buildString {
        appendLine("status=$status")
        appendLine("stage=resident_parameter_idle")
        appendLine("source_gamma=${sample.sourceGamma.orEmpty()}")
        appendLine("source_kind=$sourceKind")
        appendLine("resident_shader_attached=$residentAttached")
        appendLine("parameter_publish_count=$totalUpdateCount")
        appendLine("burst_publish_count=$burstUpdateCount")
        appendLine("burst_shader_list_mutations=$burstShaderListMutations")
        appendLine("readback_matches_requested=$readbackMatches")
        appendLine("resident_shader_unchanged=$shaderUnchanged")
        appendLine("last_command_latency_us=${nanosToMicros(sample.commandLatencyNanos)}")
        appendLine("last_set_plus_readback_latency_us=${nanosToMicros(sample.setAndReadbackLatencyNanos)}")
        appendLine("average_command_latency_us=${nanosToMicros(avgCommandLatencyNanos)}")
        appendLine("max_command_latency_us=${nanosToMicros(maxCommandLatencyNanos)}")
        appendLine("frame_drop_before=${formatCount(first.frameDropCount)}")
        appendLine("frame_drop_after=${formatCount(sample.frameDropCount)}")
        appendLine("frame_drop_delta=${formatDelta(first.frameDropCount, sample.frameDropCount)}")
        appendLine("decoder_drop_before=${formatCount(first.decoderFrameDropCount)}")
        appendLine("decoder_drop_after=${formatCount(sample.decoderFrameDropCount)}")
        appendLine("decoder_drop_delta=${formatDelta(first.decoderFrameDropCount, sample.decoderFrameDropCount)}")
        appendLine("mistimed_before=${formatCount(first.mistimedFrameCount)}")
        appendLine("mistimed_after=${formatCount(sample.mistimedFrameCount)}")
        appendLine("mistimed_delta=${formatDelta(first.mistimedFrameCount, sample.mistimedFrameCount)}")
        appendLine("vo_delayed_before=${formatCount(first.voDelayedFrameCount)}")
        appendLine("vo_delayed_after=${formatCount(sample.voDelayedFrameCount)}")
        appendLine("vo_delayed_delta=${formatDelta(first.voDelayedFrameCount, sample.voDelayedFrameCount)}")
        appendLine("thermal_start=${thermalName(burstStartThermal)}")
        appendLine("thermal_end=${thermalName(thermalEnd)}")
        appendLine("resident_shader_bytes=${currentShader.bytes}")
        appendLine("resident_shader_modified_ms=${currentShader.modifiedMillis}")
        appendLine("resident_shader_sha256=${currentShader.sha256.orEmpty()}")
        appendLine("requested_opts=${sample.requestedOptions}")
        appendLine("readback_opts=${sample.readbackOptions.orEmpty()}")
      },
    )

    burstActive = false
    burstStartSample = null
    burstUpdateCount = 0L
    burstShaderListMutations = 0L
    burstStartThermal = null
  }

  private fun captureFileSnapshot(target: File): FileSnapshot =
    runCatching {
      if (!target.isFile) return@runCatching FileSnapshot(false, 0L, 0L, null)
      FileSnapshot(
        exists = true,
        bytes = target.length(),
        modifiedMillis = target.lastModified(),
        sha256 = sha256(target),
      )
    }.getOrElse { FileSnapshot(false, 0L, 0L, null) }

  private fun sha256(target: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    target.inputStream().buffered().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(Locale.US, byte) }
  }

  private fun thermalStatus(): Int? = runCatching { powerManager?.currentThermalStatus }.getOrNull()

  private fun thermalName(value: Int?): String =
    when (value) {
      PowerManager.THERMAL_STATUS_NONE -> "NONE"
      PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
      PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
      PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
      PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
      PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
      PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
      else -> "UNAVAILABLE"
    }

  private fun classifySource(gamma: String?): String {
    val normalized = gamma?.trim()?.lowercase(Locale.US).orEmpty()
    return when {
      normalized.isBlank() -> "UNKNOWN"
      normalized.contains("pq") || normalized.contains("st2084") || normalized.contains("smpte2084") -> "HDR_PQ"
      normalized.contains("hlg") || normalized.contains("arib") -> "HDR_HLG"
      else -> "SDR"
    }
  }

  private fun nanosToMicros(value: Long): String =
    String.format(Locale.US, "%.3f", value / 1_000.0)

  private fun formatCount(value: Long?): String = value?.toString() ?: "UNAVAILABLE"

  private fun formatDelta(before: Long?, after: Long?): String =
    if (before == null || after == null) "UNAVAILABLE" else (after - before).toString()

  private fun writeAtomically(text: String) {
    runCatching {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, ".${file.name}.tmp")
      temp.writeText(text)
      if (!temp.renameTo(file)) {
        file.writeText(text)
        temp.delete()
      }
    }
  }
}

internal fun createR08InstrumentedMpvShaderLabBridge(
  context: Context,
  engineInstaller: ShaderLabEngineInstaller,
): MpvShaderLabBridge =
  MpvShaderLabBridge(
    transport =
      ShaderLabR08ProbedMpvTransport(
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
