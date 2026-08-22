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
    if (attached) MPVLib.removeObserver(observer)
    this.listener = listener
    MPVLib.addObserver(observer)
    attached = true
  }

  override fun detach() {
    if (attached) runCatching { MPVLib.removeObserver(observer) }
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
 * Observable Shader Lab backend.
 *
 * R07 established the event-driven MPV/Lua state bridge. R08 keeps that
 * boundary for persistence/presets, but moves ordinary shader tuning and
 * comparison to native Android-owned resident vo=gpu state:
 * validated values -> complete glsl-shader-opts -> resident PARAM uniforms.
 * Normal tuning and compare perform no shader file I/O and no shader list
 * mutation.
 */
class MpvShaderLabBridge internal constructor(
  private val transport: ShaderLabMpvTransport,
  private val syncProbe: ShaderLabBridgeSyncProbe = NoOpShaderLabBridgeSyncProbe,
  private val prepareEngine: () -> Unit = {},
  private val schedule: (Long, () -> Unit) -> Unit = { _, _ -> Unit },
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
    schedule = { delayMillis, task ->
      android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(task, delayMillis)
    },
  )

  private val commandLock = Any()
  private val residentGpu = ShaderLabResidentGpuTransport(transport)
  private val nativeCompare = ShaderLabNativeComparisonController(transport, residentGpu)
  private val _state = MutableStateFlow(ShaderLabBackendState())
  val state: StateFlow<ShaderLabBackendState> = _state.asStateFlow()

  private val _events = MutableSharedFlow<ShaderLabBridgeEvent>(extraBufferCapacity = 32)
  val events: SharedFlow<ShaderLabBridgeEvent> = _events.asSharedFlow()

  private var attached = false
  private var enginePrepared = false
  private var lastEventError: String? = null
  private var adoptResidentFromLuaOnIdle = false
  private var lastObservedPath: String? = null

  /** Called after mpv_create and before mpv_initialize. */
  fun prepareForMpvInitialization(): String? =
    synchronized(commandLock) {
      runCatching {
        syncProbe.stage("preinit_engine_prepare")
        ensureEnginePrepared()
        syncProbe.stage("preinit_script_option", CONTROLLER_PATH)
        CONTROLLER_PATH
      }.onFailure(::recordTransportFailure).getOrNull()
    }

  fun attach() {
    synchronized(commandLock) {
      runCatching {
        syncProbe.stage("engine_prepare")
        ensureEnginePrepared()
        syncProbe.stage("engine_ready")
        if (attached) transport.detach()
        transport.attach(::onObservedProperty)
        attached = true
        _state.value = _state.value.copy(connected = true)
        syncProbe.stage("observer_attached")

        transport.observeString(NATIVE_STATE_PROPERTY)
        transport.observeString(USER_DATA_ROOT_PROPERTY)
        transport.observeString(SOURCE_GAMMA_PROPERTY)
        transport.observeString(PATH_PROPERTY)
        MPV_PROPERTY_CONTROLS.forEach { transport.observeDouble(it.id.legacyKey) }

        val nativeState = readNativeState()
        nativeState
          ?.takeIf { it.isNotBlank() }
          ?.let(::consumeNativeState)
        transport.getString(SOURCE_GAMMA_PROPERTY)?.let(::consumeSourceGamma)
        MPV_PROPERTY_CONTROLS.forEach { spec ->
          transport.getDouble(spec.id.legacyKey)?.let { consumeDirectControlValue(spec.id, it) }
        }

        residentGpu.initialize(_state.value.values, _state.value.sourceKind)
        nativeCompare.captureVideoStart(_state.value.values, _state.value.sourceKind)
        _state.value =
          _state.value.copy(
            bypassed = false,
            previewOriginal = false,
            values = overlayAuthoritativeValues(_state.value.values),
          )
        lastObservedPath = transport.getString(PATH_PROPERTY)?.takeIf { it.isNotBlank() }

        // load-script readiness is not synchronous across mpv versions.
        if (nativeState.isNullOrBlank()) {
          syncProbe.stage("load_script_requested", CONTROLLER_PATH)
          transport.command("load-script", CONTROLLER_PATH)
        }
        requestNativeStateHandshake("initial")
        HANDSHAKE_DELAYS_MS.forEachIndexed { index, delayMillis ->
          schedule(delayMillis) {
            synchronized(commandLock) {
              if (!attached || _state.value.ready) return@synchronized
              runCatching { requestNativeStateHandshake("retry_${index + 1}") }
                .onFailure(::recordTransportFailure)
            }
          }
        }
        schedule(HANDSHAKE_TIMEOUT_MS) {
          synchronized(commandLock) {
            if (attached && !_state.value.ready) {
              val directState = transport.getString(NATIVE_STATE_PROPERTY) ?: "<null>"
              val userDataRoot = transport.getString(USER_DATA_ROOT_PROPERTY) ?: "<null>"
              syncProbe.stage(
                "timeout",
                "No native-state snapshot after direct/root user-data read; direct=${directState.take(120)}; root=${userDataRoot.take(240)}",
              )
            }
          }
        }
        _events.tryEmit(ShaderLabBridgeEvent.Attached)
      }.onFailure(::recordTransportFailure)
    }
  }

  fun detach() {
    synchronized(commandLock) {
      if (attached) {
        runCatching { nativeCompare.forceTunedView(_state.value.sourceKind) }
          .onFailure(::recordTransportFailure)
      }
      runCatching { transport.detach() }.onFailure(::recordTransportFailure)
      attached = false
      nativeCompare.onDetached()
      residentGpu.onDetached()
      adoptResidentFromLuaOnIdle = false
      lastObservedPath = null
      _state.value =
        _state.value.copy(
          connected = false,
          ready = false,
          bypassed = false,
          previewOriginal = false,
          applyBusy = false,
        )
      _events.tryEmit(ShaderLabBridgeEvent.Detached)
    }
  }

  fun requestStateRefresh() {
    serializedCommand { requestNativeStateHandshake("manual") }
  }

  fun toggleLegacyOverlay() {
    runCatching {
      serializedCommand { transport.command("script-message", "p9lab-toggle-ui") }
    }
  }

  override fun snapshotValues(): Map<ShaderLabControlId, Double> = _state.value.values

  /**
   * R08 ordinary tuning path. A shader/master change sends one complete
   * glsl-shader-opts value; MPV properties are applied directly unless an
   * ORIGINAL comparison is currently visible. Only non-render controller
   * compatibility values still use Lua messages.
   */
  override fun setValues(values: Map<ShaderLabControlId, Double>) {
    serializedCommand {
      values.forEach { (id, value) ->
        require(value.isFinite()) { "Non-finite Shader Lab value for ${id.legacyKey}: $value" }
      }

      val merged = _state.value.values.toMutableMap()
      values.forEach { (id, value) -> merged[id] = ShaderLabControlCatalog.spec(id).clamp(value) }
      val normalized = ShaderLabControlCatalog.normalizeValues(merged, values.keys.singleOrNull())

      if (values.keys.any(residentGpu::isResidentControl)) {
        residentGpu.publish(normalized)
      }

      values.keys.forEach { id ->
        val spec = ShaderLabControlCatalog.spec(id)
        when {
          residentGpu.isResidentControl(id) -> Unit
          nativeCompare.isPictureProperty(id) -> {
            nativeCompare.setTunedValue(id, normalized.getValue(id))
          }
          else -> {
            if (spec.kind == ShaderLabControlKind.MORPH) adoptResidentFromLuaOnIdle = true
            transport.command(
              "script-message",
              "p9lab-native-set",
              spec.id.legacyKey,
              formatDouble(normalized.getValue(id)),
            )
          }
        }
      }

      _state.value =
        _state.value.copy(
          values = overlayAuthoritativeValues(normalized),
          lastError = null,
        )
    }
  }

  override fun toggleBypass() {
    serializedCommand {
      val next = nativeCompare.toggleBypass(_state.value.sourceKind)
      publishNativeCompareState(next)
    }
  }

  override fun setPreviewOriginal(active: Boolean) {
    serializedCommand {
      val next = nativeCompare.setPreviewOriginal(active, _state.value.sourceKind)
      publishNativeCompareState(next)
    }
  }

  override fun togglePreviewOriginalFallback() {
    serializedCommand {
      val next = nativeCompare.togglePreviewOriginalFallback(_state.value.sourceKind)
      publishNativeCompareState(next)
    }
  }

  override fun revertVideoStart() =
    scriptMessageAdoptingValues("p9lab-native-revert-video-start")

  override fun resetAll() =
    scriptMessageAdoptingValues("p9lab-native-reset-all")

  override fun saveUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessage(
      "p9lab-native-user-save-r08",
      preset.slot.toString(),
      luaStateSnapshot(),
    )

  override fun loadUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessageAdoptingValues("p9lab-native-user-load", preset.slot.toString())

  override fun clearUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessage("p9lab-native-user-clear", preset.slot.toString())

  override fun loadBuiltInPreset(preset: ShaderLabPresetId.BuiltIn) =
    scriptMessageAdoptingValues("p9lab-native-builtin-load", preset.slot.toString())

  override fun morph(from: ShaderLabPresetId, to: ShaderLabPresetId, amount: Double) =
    scriptMessageAdoptingValues(
      "p9lab-native-morph",
      from.toLegacyMorphReference().toString(),
      to.toLegacyMorphReference().toString(),
      String.format(Locale.US, "%.17g", amount),
    )

  override fun saveState() =
    scriptMessage("p9lab-native-save-state-r08", luaStateSnapshot())

  override fun loadState() =
    scriptMessageAdoptingValues("p9lab-native-load-state")

  private fun ensureEnginePrepared() {
    if (enginePrepared) return
    prepareEngine()
    enginePrepared = true
  }

  private fun requestNativeStateHandshake(stage: String) {
    syncProbe.stage("handshake_$stage")
    val current = readNativeState()
    if (!current.isNullOrBlank()) {
      consumeNativeState(current)
      return
    }
    transport.command("script-message", "p9lab-native-state")
  }

  private fun readNativeState(): String? =
    transport.getString(NATIVE_STATE_PROPERTY)
      ?.takeIf { it.isNotBlank() }
      ?: transport.getString(USER_DATA_ROOT_PROPERTY)
        ?.let(ShaderLabUserDataCodec::extractNativeState)
        ?.takeIf { it.isNotBlank() }

  private fun scriptMessage(name: String, vararg args: String) {
    serializedCommand { transport.command("script-message", name, *args) }
  }

  private fun scriptMessageAdoptingValues(name: String, vararg args: String) {
    serializedCommand {
      adoptResidentFromLuaOnIdle = true
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
    synchronized(commandLock) {
      when {
        property == NATIVE_STATE_PROPERTY && value is ShaderLabMpvValue.Text -> {
          consumeNativeState(value.value)
        }
        property == USER_DATA_ROOT_PROPERTY && value is ShaderLabMpvValue.Text -> {
          ShaderLabUserDataCodec.extractNativeState(value.value)?.let(::consumeNativeState)
        }
        property == SOURCE_GAMMA_PROPERTY && value is ShaderLabMpvValue.Text -> {
          consumeSourceGamma(value.value)
        }
        property == PATH_PROPERTY && value is ShaderLabMpvValue.Text -> {
          consumePath(value.value)
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
  }

  private fun consumePath(path: String) {
    val normalized = path.trim()
    if (normalized.isEmpty() || normalized == lastObservedPath) return
    lastObservedPath = normalized
    runCatching {
      nativeCompare.captureVideoStart(_state.value.values, _state.value.sourceKind)
      _state.value =
        _state.value.copy(
          bypassed = false,
          previewOriginal = false,
          values = overlayAuthoritativeValues(_state.value.values),
        )
    }.onFailure(::recordTransportFailure)
  }

  private fun consumeNativeState(raw: String) {
    val previous = _state.value
    var decoded = ShaderLabNativeStateCodec.decode(raw, previous)
    val legacyShaderSwapChanged = decoded.shaderSwapCount != previous.shaderSwapCount
    val firstReadySnapshot = !previous.ready && decoded.ready

    if (residentGpu.isAuthoritative()) {
      if (!decoded.applyBusy && (firstReadySnapshot || adoptResidentFromLuaOnIdle || legacyShaderSwapChanged)) {
        runCatching {
          residentGpu.adoptLegacyValues(decoded.values, decoded.sourceKind)
          nativeCompare.seedTunedValues(decoded.values)
        }.onFailure(::recordTransportFailure)
        adoptResidentFromLuaOnIdle = false
      } else if (decoded.sourceKind != previous.sourceKind) {
        runCatching { residentGpu.reconcileSource(decoded.sourceKind) }
          .onFailure(::recordTransportFailure)
      }

      // Lua's R07 bypass/preview fields are intentionally non-authoritative in
      // R08. Those legacy commands remove/regenerate shader slots. Android owns
      // compare state so a stale Lua snapshot can never trigger that path or
      // undo the private resident R08_BYPASS PARAM.
      val compare = nativeCompare.state
      decoded =
        decoded.copy(
          bypassed = compare.bypassed,
          previewOriginal = compare.previewOriginal,
          values = overlayAuthoritativeValues(decoded.values),
        )
    }

    _state.value = decoded.copy(connected = true)
    runCatching { syncProbe.record(_state.value) }
    _events.tryEmit(ShaderLabBridgeEvent.SnapshotReceived(decoded.snapshotSerial))
    decoded.lastError?.let(::emitBackendErrorIfChanged)
  }

  private fun consumeSourceGamma(gamma: String) {
    val normalized = gamma.trim().lowercase(Locale.US)
    val kind = sourceKind(normalized)
    val previous = _state.value

    if (previous.sourceKind == ShaderLabSourceKind.SDR && kind != ShaderLabSourceKind.SDR) {
      runCatching { nativeCompare.forceTunedView(previous.sourceKind) }
        .onFailure(::recordTransportFailure)
    }

    _state.value =
      previous.copy(
        sourceGamma = normalized.ifBlank { null },
        sourceKind = kind,
        sdrEligible = kind == ShaderLabSourceKind.SDR,
        bypassed = nativeCompare.state.bypassed,
        previewOriginal = nativeCompare.state.previewOriginal,
      )

    if (residentGpu.isAuthoritative() && kind != previous.sourceKind) {
      runCatching {
        residentGpu.reconcileSource(kind)
        if (kind == ShaderLabSourceKind.SDR) {
          nativeCompare.captureVideoStart(_state.value.values, kind)
          _state.value =
            _state.value.copy(
              bypassed = false,
              previewOriginal = false,
              values = overlayAuthoritativeValues(_state.value.values),
            )
        }
      }.onFailure(::recordTransportFailure)
    }
  }

  private fun consumeDirectControlValue(id: ShaderLabControlId, value: Double) {
    if (!value.isFinite()) return
    nativeCompare.adoptObservedTunedValue(id, value)
    val values = _state.value.values.toMutableMap()
    values[id] = ShaderLabControlCatalog.spec(id).clamp(value)
    _state.value = _state.value.copy(values = overlayAuthoritativeValues(values))
  }

  private fun publishNativeCompareState(next: ShaderLabNativeComparisonController.State) {
    _state.value =
      _state.value.copy(
        bypassed = next.bypassed,
        previewOriginal = next.previewOriginal,
        values = overlayAuthoritativeValues(_state.value.values),
        lastError = null,
      )
  }

  private fun overlayAuthoritativeValues(
    values: Map<ShaderLabControlId, Double>,
  ): Map<ShaderLabControlId, Double> =
    nativeCompare.overlayTunedValues(residentGpu.overlayResidentValues(values))

  private fun recordTransportFailure(error: Throwable) {
    val message = error.message ?: "Shader Lab MPV transport failure"
    _state.value = _state.value.copy(lastError = message, applyBusy = false)
    runCatching { syncProbe.stage("transport_error", message) }
    emitBackendErrorIfChanged(message)
  }

  private fun emitBackendErrorIfChanged(message: String) {
    if (message == lastEventError) return
    lastEventError = message
    _events.tryEmit(ShaderLabBridgeEvent.BackendError(message))
  }

  private fun luaStateSnapshot(): String {
    val normalized = ShaderLabControlCatalog.normalizeValues(_state.value.values)
    return ShaderLabControlCatalog.controls.joinToString(";") { spec ->
      "${spec.id.legacyKey}=${formatDouble(normalized.getValue(spec.id))}"
    }
  }

  companion object {
    const val NATIVE_STATE_PROPERTY = "user-data/p9lab/native-state"
    const val USER_DATA_ROOT_PROPERTY = "user-data"
    const val SOURCE_GAMMA_PROPERTY = "video-params/gamma"
    const val PATH_PROPERTY = "path"
    const val CONTROLLER_PATH = "/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua"

    private val HANDSHAKE_DELAYS_MS = longArrayOf(100L, 300L, 750L, 1500L)
    private const val HANDSHAKE_TIMEOUT_MS = 2500L

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

internal object ShaderLabUserDataCodec {
  private const val NATIVE_STATE_KEY = "native-state"

  fun extractNativeState(raw: String): String? {
    val keyIndex = raw.indexOf(NATIVE_STATE_KEY)
    if (keyIndex < 0) return null
    val colonIndex = raw.indexOf(':', keyIndex + NATIVE_STATE_KEY.length)
    if (colonIndex < 0) return null

    var index = colonIndex + 1
    while (index < raw.length && raw[index].isWhitespace()) index += 1
    if (index >= raw.length || raw[index] != '"') return null
    index += 1

    val encoded = StringBuilder()
    var escaped = false
    while (index < raw.length) {
      val char = raw[index++]
      if (!escaped && char == '"') return decodeJsonString(encoded.toString())
      encoded.append(char)
      if (escaped) {
        escaped = false
      } else if (char == '\\') {
        escaped = true
      }
    }
    return null
  }

  private fun decodeJsonString(encoded: String): String? {
    val decoded = StringBuilder(encoded.length)
    var index = 0
    while (index < encoded.length) {
      val char = encoded[index++]
      if (char != '\\') {
        decoded.append(char)
        continue
      }
      if (index >= encoded.length) return null
      when (val escaped = encoded[index++]) {
        '"', '\\', '/' -> decoded.append(escaped)
        'b' -> decoded.append('\b')
        'f' -> decoded.append('\u000C')
        'n' -> decoded.append('\n')
        'r' -> decoded.append('\r')
        't' -> decoded.append('\t')
        'u' -> {
          if (index + 4 > encoded.length) return null
          val codePoint = encoded.substring(index, index + 4).toIntOrNull(16) ?: return null
          decoded.append(codePoint.toChar())
          index += 4
        }
        else -> return null
      }
    }
    return decoded.toString()
  }
}

internal object ShaderLabNativeStateCodec {
  fun decode(raw: String, previous: ShaderLabBackendState): ShaderLabBackendState {
    val fields = linkedMapOf<String, String>()
    raw.lineSequence().forEach { line ->
      val separator = line.indexOf('=')
      if (separator > 0) fields[line.substring(0, separator)] = line.substring(separator + 1)
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
