package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstallState
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlKind
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabPresetId
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandBackend
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ShaderLabSourceKind {
  NOT_READY,
  SDR,
  HDR_PQ,
  HDR_HLG,
  UNKNOWN,
}

enum class ShaderLabBank {
  A,
  B,
  UNKNOWN,
}

enum class ShaderLabShaderSlot {
  A,
  B,
  UNKNOWN,
}

data class ShaderLabBackendState(
  val connected: Boolean = false,
  val ready: Boolean = false,
  val backendVersion: String? = null,
  val sourceGamma: String? = null,
  val sourceKind: ShaderLabSourceKind = ShaderLabSourceKind.NOT_READY,
  val sdrEligible: Boolean = false,
  val activeBank: ShaderLabBank = ShaderLabBank.UNKNOWN,
  val bypassed: Boolean = false,
  val previewOriginal: Boolean = false,
  val shaderSlot: ShaderLabShaderSlot = ShaderLabShaderSlot.UNKNOWN,
  val shaderSwapCount: Long = 0L,
  val applyBusy: Boolean = false,
  val lastError: String? = null,
  val snapshotSerial: Long = 0L,
  val values: Map<ShaderLabControlId, Double> = ShaderLabControlCatalog.defaults(),
  val userPresetOccupied: Set<Int> = emptySet(),
)

sealed interface ShaderLabBridgeEvent {
  data object Attached : ShaderLabBridgeEvent
  data object Detached : ShaderLabBridgeEvent
  data class SnapshotReceived(val serial: Long) : ShaderLabBridgeEvent
  data class BackendError(val message: String) : ShaderLabBridgeEvent
}

internal sealed interface ShaderLabMpvValue {
  data object Unavailable : ShaderLabMpvValue
  data class Text(val value: String) : ShaderLabMpvValue
  data class Number(val value: Double) : ShaderLabMpvValue
  data class Integer(val value: Long) : ShaderLabMpvValue
  data class Flag(val value: Boolean) : ShaderLabMpvValue
}

internal interface ShaderLabMpvTransport {
  fun attach(listener: (String, ShaderLabMpvValue) -> Unit)
  fun detach()
  fun observeString(property: String)
  fun observeDouble(property: String)
  fun getString(property: String): String?
  fun getDouble(property: String): Double?
  fun command(vararg args: String)
}

internal class LibMpvShaderLabTransport : ShaderLabMpvTransport {
  private var listener: ((String, ShaderLabMpvValue) -> Unit)? = null
  private var attached = false

  private val observer =
    object : MPVLib.EventObserver {
      override fun eventProperty(property: String) {
        listener?.invoke(property, ShaderLabMpvValue.Unavailable)
      }

      override fun eventProperty(property: String, value: Long) {
        listener?.invoke(property, ShaderLabMpvValue.Integer(value))
      }

      override fun eventProperty(property: String, value: Boolean) {
        listener?.invoke(property, ShaderLabMpvValue.Flag(value))
      }

      override fun eventProperty(property: String, value: String) {
        listener?.invoke(property, ShaderLabMpvValue.Text(value))
      }

      override fun eventProperty(property: String, value: Double) {
        listener?.invoke(property, ShaderLabMpvValue.Number(value))
      }

      override fun eventProperty(property: String, value: MPVNode) = Unit

      override fun event(eventId: Int, data: MPVNode) = Unit
    }

  override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) {
    if (attached) {
      MPVLib.removeObserver(observer)
    }
    this.listener = listener
    MPVLib.addObserver(observer)
    attached = true
  }

  override fun detach() {
    if (attached) {
      runCatching { MPVLib.removeObserver(observer) }
    }
    attached = false
    listener = null
  }

  override fun observeString(property: String) {
    MPVLib.observeProperty(property, MPVLib.MpvFormat.MPV_FORMAT_STRING)
  }

  override fun observeDouble(property: String) {
    MPVLib.observeProperty(property, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
  }

  override fun getString(property: String): String? = MPVLib.getPropertyString(property)

  override fun getDouble(property: String): Double? = MPVLib.getPropertyDouble(property)

  override fun command(vararg args: String) {
    MPVLib.command(*args)
  }
}

/**
 * Concrete observable MPV/Lua backend for the R06 semantic command API.
 *
 * R07 intentionally does not implement shader write coalescing or rollback;
 * that belongs to R08. This bridge owns transport, serialization, state decode,
 * and property observation only. There is no periodic UI polling loop.
 */
class MpvShaderLabBridge internal constructor(
  private val transport: ShaderLabMpvTransport,
  private val syncProbe: ShaderLabBridgeSyncProbe = NoOpShaderLabBridgeSyncProbe,
  private val prepareEngine: () -> Unit = {},
) : ShaderLabCommandBackend {
  constructor(engineInstaller: ShaderLabEngineInstaller) : this(
    transport = LibMpvShaderLabTransport(),
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
  )

  private val commandLock = Any()
  private val _state = MutableStateFlow(ShaderLabBackendState())
  val state: StateFlow<ShaderLabBackendState> = _state.asStateFlow()

  private val _events = MutableSharedFlow<ShaderLabBridgeEvent>(extraBufferCapacity = 32)
  val events: SharedFlow<ShaderLabBridgeEvent> = _events.asSharedFlow()

  private var attached = false
  private var lastEventError: String? = null

  fun attach() {
    synchronized(commandLock) {
      runCatching {
        prepareEngine()
        if (attached) {
          transport.detach()
        }
        transport.attach(::onObservedProperty)
        attached = true
        _state.value = _state.value.copy(connected = true)

        transport.observeString(NATIVE_STATE_PROPERTY)
        transport.observeString(SOURCE_GAMMA_PROPERTY)
        MPV_PROPERTY_CONTROLS.forEach { transport.observeDouble(it.id.legacyKey) }

        val nativeState = transport.getString(NATIVE_STATE_PROPERTY)
        nativeState
          ?.takeIf { it.isNotBlank() }
          ?.let(::consumeNativeState)
        transport.getString(SOURCE_GAMMA_PROPERTY)?.let(::consumeSourceGamma)
        MPV_PROPERTY_CONTROLS.forEach { spec ->
          transport.getDouble(spec.id.legacyKey)?.let { consumeDirectControlValue(spec.id, it) }
        }

        // R04 owns the readable controller in the canonical workspace, but its
        // reference mpv.conf is intentionally not forced into the user's active
        // config. R07 therefore activates the controller explicitly only when
        // no native-state publisher is already present. This makes the bridge
        // work on a clean install without duplicating a user-configured script.
        if (nativeState.isNullOrBlank()) {
          transport.command("load-script", CONTROLLER_PATH)
        }
        transport.command("script-message", "p9lab-native-state")
        _events.tryEmit(ShaderLabBridgeEvent.Attached)
      }.onFailure(::recordTransportFailure)
    }
  }

  fun detach() {
    synchronized(commandLock) {
      runCatching { transport.detach() }
        .onFailure(::recordTransportFailure)
      attached = false
      _state.value =
        _state.value.copy(
          connected = false,
          ready = false,
          applyBusy = false,
        )
      _events.tryEmit(ShaderLabBridgeEvent.Detached)
    }
  }

  fun requestStateRefresh() {
    serializedCommand {
      transport.command("script-message", "p9lab-native-state")
    }
  }

  override fun snapshotValues(): Map<ShaderLabControlId, Double> = _state.value.values

  override fun setValues(values: Map<ShaderLabControlId, Double>) {
    serializedCommand {
      ShaderLabControlCatalog.controls.forEach { spec ->
        val value = values[spec.id] ?: return@forEach
        transport.command(
          "script-message",
          "p9lab-native-set",
          spec.id.legacyKey,
          formatDouble(value),
        )
      }
    }
  }

  override fun toggleBypass() =
    scriptMessage("p9lab-native-bypass")

  override fun setPreviewOriginal(active: Boolean) =
    scriptMessage(if (active) "p9lab-native-preview-start" else "p9lab-native-preview-end")

  override fun togglePreviewOriginalFallback() =
    scriptMessage("p9lab-native-preview-toggle")

  override fun revertVideoStart() =
    scriptMessage("p9lab-native-revert-video-start")

  override fun resetAll() =
    scriptMessage("p9lab-native-reset-all")

  override fun saveUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessage("p9lab-native-user-save", preset.slot.toString())

  override fun loadUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessage("p9lab-native-user-load", preset.slot.toString())

  override fun clearUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessage("p9lab-native-user-clear", preset.slot.toString())

  override fun loadBuiltInPreset(preset: ShaderLabPresetId.BuiltIn) =
    scriptMessage("p9lab-native-builtin-load", preset.slot.toString())

  override fun morph(from: ShaderLabPresetId, to: ShaderLabPresetId, amount: Double) =
    scriptMessage(
      "p9lab-native-morph",
      from.toLegacyMorphReference().toString(),
      to.toLegacyMorphReference().toString(),
      String.format(Locale.US, "%.17g", amount),
    )

  override fun saveState() =
    scriptMessage("p9lab-native-save-state")

  override fun loadState() =
    scriptMessage("p9lab-native-load-state")

  private fun scriptMessage(name: String, vararg args: String) {
    serializedCommand {
      transport.command("script-message", name, *args)
    }
  }

  private fun serializedCommand(block: () -> Unit) {
    synchronized(commandLock) {
      try {
        check(attached) { "Shader Lab MPV bridge is not attached" }
        block()
      } catch (error: Throwable) {
        recordTransportFailure(error)
        throw error
      }
    }
  }

  private fun onObservedProperty(property: String, value: ShaderLabMpvValue) {
    when {
      property == NATIVE_STATE_PROPERTY && value is ShaderLabMpvValue.Text -> {
        consumeNativeState(value.value)
      }
      property == SOURCE_GAMMA_PROPERTY && value is ShaderLabMpvValue.Text -> {
        consumeSourceGamma(value.value)
      }
      value is ShaderLabMpvValue.Number -> {
        MPV_PROPERTY_BY_KEY[property]?.let { consumeDirectControlValue(it, value.value) }
      }
      value is ShaderLabMpvValue.Integer -> {
        MPV_PROPERTY_BY_KEY[property]?.let { consumeDirectControlValue(it, value.value.toDouble()) }
      }
      else -> Unit
    }
  }

  private fun consumeNativeState(raw: String) {
    val decoded = ShaderLabNativeStateCodec.decode(raw, _state.value)
    _state.value = decoded.copy(connected = true)
    runCatching { syncProbe.record(decoded) }
    _events.tryEmit(ShaderLabBridgeEvent.SnapshotReceived(decoded.snapshotSerial))
    decoded.lastError?.let(::emitBackendErrorIfChanged)
  }

  private fun consumeSourceGamma(gamma: String) {
    val normalized = gamma.trim().lowercase(Locale.US)
    val kind = sourceKind(normalized)
    _state.value =
      _state.value.copy(
        sourceGamma = normalized.ifBlank { null },
        sourceKind = kind,
        sdrEligible = kind == ShaderLabSourceKind.SDR,
      )
  }

  private fun consumeDirectControlValue(id: ShaderLabControlId, value: Double) {
    if (!value.isFinite()) return
    val values = _state.value.values.toMutableMap()
    values[id] = ShaderLabControlCatalog.spec(id).clamp(value)
    _state.value = _state.value.copy(values = values)
  }

  private fun recordTransportFailure(error: Throwable) {
    val message = error.message ?: "Shader Lab MPV transport failure"
    _state.value = _state.value.copy(lastError = message, applyBusy = false)
    emitBackendErrorIfChanged(message)
  }

  private fun emitBackendErrorIfChanged(message: String) {
    if (message == lastEventError) return
    lastEventError = message
    _events.tryEmit(ShaderLabBridgeEvent.BackendError(message))
  }

  companion object {
    const val NATIVE_STATE_PROPERTY = "user-data/p9lab/native-state"
    const val SOURCE_GAMMA_PROPERTY = "video-params/gamma"
    const val CONTROLLER_PATH = "/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua"

    private val MPV_PROPERTY_CONTROLS =
      ShaderLabControlCatalog.controls.filter { it.kind == ShaderLabControlKind.MPV_PROPERTY }

    private val MPV_PROPERTY_BY_KEY = MPV_PROPERTY_CONTROLS.associate { it.id.legacyKey to it.id }

    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.17g", value)

    private fun sourceKind(gamma: String): ShaderLabSourceKind =
      when (gamma.trim().lowercase(Locale.US)) {
        "" -> ShaderLabSourceKind.NOT_READY
        "pq" -> ShaderLabSourceKind.HDR_PQ
        "hlg" -> ShaderLabSourceKind.HDR_HLG
        else -> ShaderLabSourceKind.SDR
      }
  }
}

internal object ShaderLabNativeStateCodec {
  fun decode(raw: String, previous: ShaderLabBackendState): ShaderLabBackendState {
    val fields = linkedMapOf<String, String>()
    raw.lineSequence().forEach { line ->
      val separator = line.indexOf('=')
      if (separator > 0) {
        fields[line.substring(0, separator)] = line.substring(separator + 1)
      }
    }

    val values = previous.values.toMutableMap()
    ShaderLabControlCatalog.controls.forEach { spec ->
      fields[spec.id.legacyKey]
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?.let { values[spec.id] = spec.clamp(it) }
    }

    val gamma = fields["__source_gamma"]?.trim()?.lowercase(Locale.US)
      ?: previous.sourceGamma.orEmpty()
    val sdr = fields["__sdr"]?.asBooleanFlag() ?: (sourceKind(gamma) == ShaderLabSourceKind.SDR)
    val occupiedFieldsPresent = (1..10).any { fields.containsKey("__user$it") }
    val occupied =
      if (occupiedFieldsPresent) {
        (1..10).filterTo(linkedSetOf()) { fields["__user$it"].asBooleanFlag() }
      } else {
        previous.userPresetOccupied
      }

    return previous.copy(
      connected = true,
      ready = fields["__ready"].asBooleanFlag() || fields["__version"] != null,
      backendVersion = fields["__version"] ?: previous.backendVersion,
      sourceGamma = gamma.ifBlank { null },
      sourceKind = sourceKind(gamma, sdr),
      sdrEligible = sdr,
      activeBank = fields["__bank"].toBank(previous.activeBank),
      bypassed = fields["__bypassed"]?.asBooleanFlag() ?: previous.bypassed,
      previewOriginal = fields["__preview"]?.asBooleanFlag() ?: previous.previewOriginal,
      shaderSlot = fields["__shader_slot"].toShaderSlot(previous.shaderSlot),
      shaderSwapCount = fields["__swaps"]?.toLongOrNull() ?: previous.shaderSwapCount,
      applyBusy = fields["__apply_busy"]?.asBooleanFlag() ?: previous.applyBusy,
      lastError = fields["__error"]?.trim()?.takeIf { it.isNotEmpty() },
      snapshotSerial = fields["__serial"]?.toLongOrNull() ?: previous.snapshotSerial,
      values = values,
      userPresetOccupied = occupied,
    )
  }

  private fun String?.asBooleanFlag(): Boolean = this == "1" || this.equals("true", ignoreCase = true)

  private fun String?.toBank(fallback: ShaderLabBank): ShaderLabBank =
    when (this?.uppercase(Locale.US)) {
      "A" -> ShaderLabBank.A
      "B" -> ShaderLabBank.B
      else -> fallback
    }

  private fun String?.toShaderSlot(fallback: ShaderLabShaderSlot): ShaderLabShaderSlot =
    when (this?.uppercase(Locale.US)) {
      "A" -> ShaderLabShaderSlot.A
      "B" -> ShaderLabShaderSlot.B
      else -> fallback
    }

  private fun sourceKind(gamma: String, sdr: Boolean): ShaderLabSourceKind =
    when {
      sdr -> ShaderLabSourceKind.SDR
      gamma.equals("pq", ignoreCase = true) -> ShaderLabSourceKind.HDR_PQ
      gamma.equals("hlg", ignoreCase = true) -> ShaderLabSourceKind.HDR_HLG
      gamma.isBlank() -> ShaderLabSourceKind.NOT_READY
      else -> ShaderLabSourceKind.UNKNOWN
    }

  private fun sourceKind(gamma: String): ShaderLabSourceKind =
    when (gamma.trim().lowercase(Locale.US)) {
      "" -> ShaderLabSourceKind.NOT_READY
      "pq" -> ShaderLabSourceKind.HDR_PQ
      "hlg" -> ShaderLabSourceKind.HDR_HLG
      else -> ShaderLabSourceKind.SDR
    }
}

private fun ShaderLabPresetId.toLegacyMorphReference(): Int =
  when (this) {
    is ShaderLabPresetId.BuiltIn -> slot
    is ShaderLabPresetId.User -> slot + 10
    ShaderLabPresetId.VideoStart -> error("VideoStart is not a morphable backend preset")
  }
