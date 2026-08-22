package app.marlboroadvance.mpvex.ui.player.controls

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.ShaderLabBackendState
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabActionId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabActionSpec
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlKind
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlSpec
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabGroup
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabPresetId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabStepMode
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabAdjustDirection
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommand
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandResult
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabDiagnosticView
import app.marlboroadvance.mpvex.repository.shaderlab.command.requiresConfirmation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt

/**
 * Native R08 Shader Lab surface.
 *
 * All 53 value-bearing catalog controls and all 10 legacy semantic actions are
 * browsable here. Shader controls remain on the resident vo=gpu PARAM path,
 * MPV controls stay direct MPV properties, and controller/preset/morph actions
 * enter through [ShaderLabCommandApi]. The native UI never directly sends Lua
 * command strings and never regenerates an ordinary tuning shader file.
 */
class ShaderLabR08OverlayView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), KoinComponent {
  private sealed interface SurfaceEntry {
    val group: ShaderLabGroup
    val label: String

    data class Control(val spec: ShaderLabControlSpec) : SurfaceEntry {
      override val group: ShaderLabGroup get() = spec.group
      override val label: String get() = spec.label
    }

    data class Action(val spec: ShaderLabActionSpec) : SurfaceEntry {
      override val group: ShaderLabGroup get() = spec.group
      override val label: String get() = spec.label
    }
  }

  private val bridge: MpvShaderLabBridge by inject()
  private val commandApi: ShaderLabCommandApi by inject()

  private val handler = Handler(Looper.getMainLooper())
  private var attachmentScope: CoroutineScope? = null
  private var stateJob: Job? = null
  private var backendState = ShaderLabBackendState()

  private val surfaceEntries: List<SurfaceEntry> = buildList {
    ShaderLabControlCatalog.groupOrder.forEach { group ->
      ShaderLabControlCatalog.controls
        .filter { it.group == group }
        .forEach { add(SurfaceEntry.Control(it)) }
      ShaderLabControlCatalog.actions
        .filter { it.group == group }
        .forEach { add(SurfaceEntry.Action(it)) }
    }
  }

  private val groups: List<ShaderLabGroup> =
    ShaderLabControlCatalog.groupOrder.filter { group -> surfaceEntries.any { it.group == group } }

  private var groupIndex = 0
  private var entryIndex = 0
  private var editMode = false
  private var stepMode = ShaderLabStepMode.NORMAL
  private var expanded = false
  private var previewHeld = false
  private var pendingConfirmation: ShaderLabActionId? = null
  private var statusOverride: String? = null

  private lateinit var labButton: Button
  private lateinit var panel: LinearLayout
  private lateinit var statusText: TextView
  private lateinit var groupText: TextView
  private lateinit var parameterText: TextView
  private lateinit var valueText: TextView
  private lateinit var modeText: TextView
  private lateinit var centerButton: Button
  private lateinit var stepButton: Button
  private lateinit var bypassButton: Button
  private lateinit var previewButton: Button

  init {
    check(ShaderLabControlCatalog.controls.size == 53) { "R08 surface expects all 53 Shader Lab controls" }
    check(ShaderLabControlCatalog.actions.size == 10) { "R08 surface expects all 10 Shader Lab actions" }
    check(surfaceEntries.size == 63) { "R08 surface must expose 63 total entries" }

    clipChildren = false
    clipToPadding = false
    isFocusable = true
    isFocusableInTouchMode = true
    buildUi()
    render()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    attachmentScope?.cancel()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    attachmentScope = scope
    stateJob =
      scope.launch {
        bridge.state.collectLatest { state ->
          backendState = state
          state.values[ShaderLabControlId.TOUCH_GRANULARITY]?.let { stepMode = stepModeFromValue(it) }
          render()
        }
      }
  }

  override fun onDetachedFromWindow() {
    stopBrowseRepeat()
    cancelConfirmation()
    if (previewHeld) {
      commandApi.execute(ShaderLabCommand.PreviewOriginalEnd)
      previewHeld = false
    }
    stateJob?.cancel()
    stateJob = null
    attachmentScope?.cancel()
    attachmentScope = null
    super.onDetachedFromWindow()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (!expanded) return super.dispatchKeyEvent(event)

    when (event.keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT -> {
        if (event.action == KeyEvent.ACTION_DOWN) {
          if (event.repeatCount == 0 || !editMode) handleDirection(-1)
        }
        return true
      }

      KeyEvent.KEYCODE_DPAD_RIGHT -> {
        if (event.action == KeyEvent.ACTION_DOWN) {
          if (event.repeatCount == 0 || !editMode) handleDirection(1)
        }
        return true
      }

      KeyEvent.KEYCODE_DPAD_UP -> {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !editMode) moveGroup(-1)
        return true
      }

      KeyEvent.KEYCODE_DPAD_DOWN -> {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !editMode) moveGroup(1)
        return true
      }

      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_NUMPAD_ENTER,
      -> {
        if (event.action == KeyEvent.ACTION_UP) activateCurrentEntry()
        return true
      }

      KeyEvent.KEYCODE_BACK -> {
        if (event.action == KeyEvent.ACTION_UP) setExpanded(false)
        return true
      }
    }

    return super.dispatchKeyEvent(event)
  }

  private fun buildUi() {
    removeAllViews()

    labButton =
      Button(context).apply {
        text = "LAB R08"
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        background = roundedBackground(0xD923252AL.toInt(), 22f)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { setExpanded(true) }
      }
    addView(labButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.CENTER))

    panel =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = roundedBackground(0xEE101216.toInt(), 20f)
        elevation = dp(10).toFloat()
        visibility = View.GONE
      }
    addView(panel, LayoutParams(dp(388), LayoutParams.WRAP_CONTENT, Gravity.CENTER))

    panel.addView(buildHeaderRow())

    statusText = labelView(12f, 0xFFB8C3D1.toInt(), Typeface.NORMAL)
    statusText.gravity = Gravity.CENTER
    statusText.maxLines = 2
    panel.addView(statusText, linearMatchWrap(top = 4))

    panel.addView(buildGroupRow(), linearMatchWrap(top = 10))

    parameterText = labelView(18f, Color.WHITE, Typeface.BOLD)
    parameterText.gravity = Gravity.CENTER
    parameterText.maxLines = 2
    panel.addView(parameterText, linearMatchWrap(top = 12))

    valueText = labelView(26f, Color.WHITE, Typeface.BOLD)
    valueText.gravity = Gravity.CENTER
    valueText.maxLines = 2
    panel.addView(valueText, linearMatchWrap(top = 3))

    modeText = labelView(12f, 0xFFB8C3D1.toInt(), Typeface.BOLD)
    modeText.gravity = Gravity.CENTER
    modeText.maxLines = 2
    panel.addView(modeText, linearMatchWrap(top = 3))

    panel.addView(buildThreeButtonRow(), linearMatchWrap(top = 10))
    panel.addView(buildQuickActionRow(), linearMatchWrap(top = 10))

    val hint = labelView(11f, 0xFF929EAD.toInt(), Typeface.NORMAL)
    hint.text = "53 controls + 10 actions  •  ↑/↓ groups  •  hold ◀/▶ repeats browse only"
    hint.gravity = Gravity.CENTER
    panel.addView(hint, linearMatchWrap(top = 8))
  }

  private fun buildHeaderRow(): View {
    val row = horizontalRow()
    val title = labelView(16f, Color.WHITE, Typeface.BOLD).apply {
      text = "Resident Shader Lab • FULL"
      gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
    row.addView(smallButton("✕") { setExpanded(false) }, LinearLayout.LayoutParams(dp(46), dp(40)))
    return row
  }

  private fun buildGroupRow(): View {
    val row = horizontalRow()
    groupText = labelView(14f, 0xFFE0E6EE.toInt(), Typeface.BOLD).apply {
      gravity = Gravity.CENTER
      setPadding(dp(4), 0, dp(4), 0)
      setOnClickListener { moveGroup(1) }
    }
    row.addView(smallButton("‹") { moveGroup(-1) }, LinearLayout.LayoutParams(dp(48), dp(40)))
    row.addView(groupText, LinearLayout.LayoutParams(0, dp(40), 1f))
    row.addView(smallButton("›") { moveGroup(1) }, LinearLayout.LayoutParams(dp(48), dp(40)))
    return row
  }

  private fun buildThreeButtonRow(): View {
    val row = horizontalRow()
    val left = largeButton("◀")
    centerButton = largeButton("SELECT")
    val right = largeButton("▶")

    installDirectionalTouch(left, -1)
    installDirectionalTouch(right, 1)
    centerButton.setOnClickListener { activateCurrentEntry() }

    row.addView(left, weightedButtonParams(end = 5))
    row.addView(centerButton, weightedButtonParams(start = 5, end = 5))
    row.addView(right, weightedButtonParams(start = 5))
    return row
  }

  private fun buildQuickActionRow(): View {
    val row = horizontalRow()
    stepButton = smallButton("NORMAL") { cycleStepMode() }
    bypassButton = smallButton("BYPASS") { executeCommand(ShaderLabCommand.ToggleBypass) }
    previewButton = smallButton("HOLD ORIGINAL", onClick = null)
    installPreviewHold(previewButton)

    row.addView(stepButton, weightedButtonParams(end = 4, height = 44))
    row.addView(bypassButton, weightedButtonParams(start = 4, end = 4, height = 44))
    row.addView(previewButton, weightedButtonParams(start = 4, height = 44))
    return row
  }

  private fun setExpanded(show: Boolean) {
    expanded = show
    editMode = false
    stopBrowseRepeat()
    cancelConfirmation()
    labButton.visibility = if (show) View.GONE else View.VISIBLE
    panel.visibility = if (show) View.VISIBLE else View.GONE
    if (show) requestFocus() else clearFocus()
    render()
  }

  private fun currentGroup(): ShaderLabGroup = groups[groupIndex.coerceIn(groups.indices)]

  private fun currentGroupEntries(): List<SurfaceEntry> = surfaceEntries.filter { it.group == currentGroup() }

  private fun currentEntry(): SurfaceEntry {
    val entries = currentGroupEntries()
    if (entryIndex !in entries.indices) entryIndex = 0
    return entries[entryIndex]
  }

  private fun moveEntry(direction: Int) {
    val entries = currentGroupEntries()
    if (entries.isEmpty()) return
    cancelConfirmation()
    entryIndex = wrap(entryIndex + direction, entries.size)
    selectCurrentControlIfAny()
    render()
  }

  private fun moveGroup(direction: Int) {
    if (groups.isEmpty() || editMode) return
    cancelConfirmation()
    groupIndex = wrap(groupIndex + direction, groups.size)
    entryIndex = 0
    commandApi.execute(ShaderLabCommand.SelectGroup(currentGroup()))
    selectCurrentControlIfAny()
    render()
  }

  private fun selectCurrentControlIfAny() {
    val entry = currentEntry()
    if (entry is SurfaceEntry.Control) commandApi.execute(ShaderLabCommand.SelectControl(entry.spec.id))
  }

  private fun activateCurrentEntry() {
    stopBrowseRepeat()
    when (val entry = currentEntry()) {
      is SurfaceEntry.Control -> {
        cancelConfirmation()
        editMode = !editMode
        commandApi.execute(ShaderLabCommand.SelectControl(entry.spec.id))
        render()
      }
      is SurfaceEntry.Action -> {
        editMode = false
        executeAction(entry.spec)
      }
    }
  }

  private fun handleDirection(direction: Int) {
    if (editMode) adjustCurrent(direction) else moveEntry(direction)
  }

  private fun adjustCurrent(direction: Int) {
    val entry = currentEntry() as? SurfaceEntry.Control ?: return
    val spec = entry.spec
    val current = backendState.values[spec.id] ?: spec.defaultValue
    val requested = spec.clamp(current + direction * spec.step(stepMode))

    when (spec.id) {
      ShaderLabControlId.TOUCH_GRANULARITY -> {
        val targetMode = stepModeFromValue(requested)
        if (executeCommand(ShaderLabCommand.SetValue(spec.id, requested))) stepMode = targetMode
      }
      ShaderLabControlId.DEBUG_VIEW -> {
        val mode = when (requested.roundToInt().coerceIn(0, 3)) {
          1 -> ShaderLabDiagnosticView.GAMUT
          2 -> ShaderLabDiagnosticView.LUMA
          3 -> ShaderLabDiagnosticView.BOTH
          else -> ShaderLabDiagnosticView.OFF
        }
        executeCommand(ShaderLabCommand.SetDiagnosticView(mode))
      }
      ShaderLabControlId.MORPH_AMOUNT -> applyMorph(requested)
      else -> {
        executeCommand(
          ShaderLabCommand.Adjust(
            control = spec.id,
            direction = if (direction < 0) ShaderLabAdjustDirection.DECREASE else ShaderLabAdjustDirection.INCREASE,
            stepMode = stepMode,
          )
        )
      }
    }
    render()
  }

  private fun applyMorph(amount: Double) {
    val from = presetReference(valueOf(ShaderLabControlId.MORPH_FROM))
    val to = presetReference(valueOf(ShaderLabControlId.MORPH_TO))
    executeCommand(ShaderLabCommand.Morph(from, to, amount))
  }

  private fun cycleStepMode() {
    val next = when (stepMode) {
      ShaderLabStepMode.FINE -> ShaderLabStepMode.NORMAL
      ShaderLabStepMode.NORMAL -> ShaderLabStepMode.COARSE
      ShaderLabStepMode.COARSE -> ShaderLabStepMode.FINE
    }
    val backendValue = when (next) {
      ShaderLabStepMode.FINE -> 1.0
      ShaderLabStepMode.NORMAL -> 2.0
      ShaderLabStepMode.COARSE -> 3.0
    }
    if (executeCommand(ShaderLabCommand.SetValue(ShaderLabControlId.TOUCH_GRANULARITY, backendValue))) {
      stepMode = next
    }
    render()
  }

  private fun executeAction(spec: ShaderLabActionSpec) {
    val command = actionCommand(spec.id)
    if (command.requiresConfirmation() && pendingConfirmation != spec.id) {
      pendingConfirmation = spec.id
      val message = "CONFIRM • ${spec.label} • CENTER again"
      showTransientStatus(message, 4000L, clearConfirmation = true)
      return
    }

    cancelConfirmation()
    val success = when (spec.id) {
      ShaderLabActionId.BYPASS -> null
      ShaderLabActionId.PREVIEW_TOGGLE_FALLBACK -> null
      ShaderLabActionId.LOAD_USER -> "Loaded ${userSlotLabel()}"
      ShaderLabActionId.SAVE_USER -> "Saved ${userSlotLabel()}"
      ShaderLabActionId.CLEAR_USER -> "Cleared ${userSlotLabel()}"
      ShaderLabActionId.LOAD_BUILTIN -> "Loaded ${builtInLabel()}"
      ShaderLabActionId.REVERT_VIDEO_START -> "Restored video-start state"
      ShaderLabActionId.RESET_ALL -> "Reset to V3.1 baseline"
      ShaderLabActionId.SAVE_STATE -> "Shader Lab state saved"
      ShaderLabActionId.LOAD_STATE -> "Shader Lab state loaded"
    }
    executeCommand(command, success)
  }

  private fun actionCommand(id: ShaderLabActionId): ShaderLabCommand = when (id) {
    ShaderLabActionId.BYPASS -> ShaderLabCommand.ToggleBypass
    ShaderLabActionId.PREVIEW_TOGGLE_FALLBACK -> ShaderLabCommand.TogglePreviewOriginalFallback
    ShaderLabActionId.LOAD_USER -> ShaderLabCommand.LoadUserPreset(ShaderLabPresetId.User(userSlot()))
    ShaderLabActionId.SAVE_USER -> ShaderLabCommand.SaveUserPreset(ShaderLabPresetId.User(userSlot()))
    ShaderLabActionId.CLEAR_USER -> ShaderLabCommand.ClearUserPreset(ShaderLabPresetId.User(userSlot()))
    ShaderLabActionId.LOAD_BUILTIN -> ShaderLabCommand.LoadBuiltInPreset(ShaderLabPresetId.BuiltIn(builtInSlot()))
    ShaderLabActionId.REVERT_VIDEO_START -> ShaderLabCommand.RevertVideoStart
    ShaderLabActionId.RESET_ALL -> ShaderLabCommand.ResetAll
    ShaderLabActionId.SAVE_STATE -> ShaderLabCommand.SaveState
    ShaderLabActionId.LOAD_STATE -> ShaderLabCommand.LoadState
  }

  /** Browse may accelerate while held. Edit mode always applies exactly one step on release. */
  private fun installDirectionalTouch(button: Button, direction: Int) {
    button.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          if (editMode) {
            stopBrowseRepeat()
          } else {
            moveEntry(direction)
            startBrowseRepeat(direction)
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          stopBrowseRepeat()
          if (editMode) adjustCurrent(direction)
          true
        }
        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_OUTSIDE,
        -> {
          stopBrowseRepeat()
          true
        }
        else -> true
      }
    }
  }

  private var browseRepeatRunnable: Runnable? = null
  private var browseRepeatCount = 0

  private fun startBrowseRepeat(direction: Int) {
    stopBrowseRepeat()
    browseRepeatCount = 0
    val runnable = object : Runnable {
      override fun run() {
        if (editMode || !expanded) return
        moveEntry(direction)
        browseRepeatCount += 1
        val delay = when {
          browseRepeatCount < 5 -> 150L
          browseRepeatCount < 14 -> 95L
          else -> 60L
        }
        handler.postDelayed(this, delay)
      }
    }
    browseRepeatRunnable = runnable
    handler.postDelayed(runnable, 430L)
  }

  private fun stopBrowseRepeat() {
    browseRepeatRunnable?.let(handler::removeCallbacks)
    browseRepeatRunnable = null
    browseRepeatCount = 0
  }

  private fun installPreviewHold(button: Button) {
    button.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          if (!previewHeld) {
            previewHeld = true
            executeCommand(ShaderLabCommand.PreviewOriginalStart)
          }
          true
        }
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_OUTSIDE,
        -> {
          if (previewHeld) {
            previewHeld = false
            executeCommand(ShaderLabCommand.PreviewOriginalEnd)
          }
          true
        }
        else -> true
      }
    }
  }

  private fun executeCommand(command: ShaderLabCommand, successMessage: String? = null): Boolean {
    val result = commandApi.execute(command)
    when (result) {
      is ShaderLabCommandResult.Applied -> {
        if (successMessage != null) showTransientStatus(successMessage)
      }
      is ShaderLabCommandResult.Rejected -> showTransientStatus("R08 ERROR • ${result.reason}", 2600L)
      is ShaderLabCommandResult.Failed -> showTransientStatus("R08 ERROR • ${result.reason}", 2600L)
    }
    if (successMessage == null && result is ShaderLabCommandResult.Applied) render()
    return result is ShaderLabCommandResult.Applied
  }

  private fun showTransientStatus(
    message: String,
    durationMs: Long = 1800L,
    clearConfirmation: Boolean = false,
  ) {
    statusOverride = message
    render()
    handler.postDelayed({
      if (statusOverride == message) {
        statusOverride = null
        if (clearConfirmation) pendingConfirmation = null
        render()
      }
    }, durationMs)
  }

  private fun cancelConfirmation() {
    pendingConfirmation = null
  }

  private fun render() {
    if (!::statusText.isInitialized || groups.isEmpty()) return

    val entry = currentEntry()
    val connection = when {
      backendState.ready -> "READY"
      backendState.connected -> "SYNCING"
      else -> "OFFLINE"
    }
    val source = backendState.sourceKind.name.replace('_', '-')
    val backendError = backendState.lastError?.takeIf { it.isNotBlank() }
    statusText.text = statusOverride ?: backendError?.let { "R08 ERROR • $it" } ?: "R08 • $connection • $source • ${entryPath(entry)}"

    groupText.text = prettyGroup(currentGroup())
    parameterText.text = entry.label
    valueText.text = entryValue(entry)

    modeText.text = when (entry) {
      is SurfaceEntry.Control -> {
        if (editMode) "EDIT • ${stepMode.name} • ±${entry.spec.step(stepMode)}"
        else "BROWSE • ${entryIndex + 1}/${currentGroupEntries().size} • ${entry.spec.kind.name.replace('_', ' ')}"
      }
      is SurfaceEntry.Action -> {
        if (pendingConfirmation == entry.spec.id) "DESTRUCTIVE ACTION • CENTER confirms"
        else "ACTION • ${entryIndex + 1}/${currentGroupEntries().size}${if (entry.spec.destructive) " • confirmation required" else ""}"
      }
    }

    centerButton.text = when (entry) {
      is SurfaceEntry.Control -> if (editMode) "DONE" else "SELECT"
      is SurfaceEntry.Action -> if (pendingConfirmation == entry.spec.id) "CONFIRM" else "RUN"
    }
    centerButton.background = roundedBackground(
      when {
        pendingConfirmation != null -> 0xFF8A4A26.toInt()
        editMode -> 0xFF335C99.toInt()
        entry is SurfaceEntry.Action -> 0xFF3A414B.toInt()
        else -> 0xFF2A2E35.toInt()
      },
      14f,
    )

    stepButton.text = stepMode.name
    bypassButton.text = if (backendState.bypassed) "BYPASSED" else "BYPASS"
    previewButton.text = if (previewHeld || backendState.previewOriginal) "ORIGINAL" else "HOLD ORIGINAL"
  }

  private fun entryValue(entry: SurfaceEntry): String = when (entry) {
    is SurfaceEntry.Control -> formatControlValue(entry.spec, valueOf(entry.spec.id))
    is SurfaceEntry.Action -> when (entry.spec.id) {
      ShaderLabActionId.LOAD_USER,
      ShaderLabActionId.SAVE_USER,
      ShaderLabActionId.CLEAR_USER,
      -> userSlotLabel()
      ShaderLabActionId.LOAD_BUILTIN -> builtInLabel()
      ShaderLabActionId.BYPASS -> if (backendState.bypassed) "ACTIVE BYPASS" else "TUNING ACTIVE"
      ShaderLabActionId.PREVIEW_TOGGLE_FALLBACK -> if (backendState.previewOriginal) "ORIGINAL" else "TUNED"
      else -> if (entry.spec.destructive) "PRESS CENTER ×2" else "PRESS CENTER"
    }
  }

  private fun formatControlValue(spec: ShaderLabControlSpec, value: Double): String {
    return when (spec.id) {
      ShaderLabControlId.USER_SLOT -> userSlotLabel(value.roundToInt())
      ShaderLabControlId.BUILTIN_SLOT -> builtInLabel(value.roundToInt())
      ShaderLabControlId.MORPH_FROM,
      ShaderLabControlId.MORPH_TO,
      -> morphReferenceLabel(value.roundToInt())
      else -> {
        if (spec.choices.isNotEmpty()) {
          val index = (value.roundToInt() - spec.minValue.roundToInt()).coerceIn(0, spec.choices.lastIndex)
          "${spec.choices[index]}  (${spec.format(value)})"
        } else {
          spec.format(value)
        }
      }
    }
  }

  private fun entryPath(entry: SurfaceEntry): String = when (entry) {
    is SurfaceEntry.Action -> "ACTION"
    is SurfaceEntry.Control -> when {
      entry.spec.kind == ShaderLabControlKind.MPV_PROPERTY -> "MPV"
      entry.spec.kind == ShaderLabControlKind.CONTROLLER -> "CONTROLLER"
      entry.spec.kind == ShaderLabControlKind.MORPH -> "MORPH"
      entry.spec.kind == ShaderLabControlKind.GRANULARITY -> "CONTROL"
      entry.spec.kind == ShaderLabControlKind.SHADER ||
        entry.spec.id == ShaderLabControlId.LUMA_MASTER ||
        entry.spec.id == ShaderLabControlId.CHROMA_MASTER ||
        entry.spec.id == ShaderLabControlId.SHADER_PROOF -> "RESIDENT"
      else -> "CONTROL"
    }
  }

  private fun valueOf(id: ShaderLabControlId): Double {
    val spec = ShaderLabControlCatalog.spec(id)
    return spec.clamp(backendState.values[id] ?: spec.defaultValue)
  }

  private fun userSlot(): Int = valueOf(ShaderLabControlId.USER_SLOT).roundToInt().coerceIn(1, 10)

  private fun builtInSlot(): Int = valueOf(ShaderLabControlId.BUILTIN_SLOT).roundToInt().coerceIn(1, 10)

  private fun userSlotLabel(slot: Int = userSlot()): String {
    val bounded = slot.coerceIn(1, 10)
    val occupied = bounded in backendState.userPresetOccupied
    return "U%02d • %s".format(bounded, if (occupied) "SAVED" else "EMPTY")
  }

  private fun builtInLabel(slot: Int = builtInSlot()): String {
    val bounded = slot.coerceIn(1, 10)
    val preset = ShaderLabControlCatalog.builtInPresets.first { it.id.slot == bounded }
    return "B%02d • %s".format(bounded, preset.name)
  }

  private fun morphReferenceLabel(reference: Int): String {
    val bounded = reference.coerceIn(1, 20)
    return if (bounded <= 10) builtInLabel(bounded) else userSlotLabel(bounded - 10)
  }

  private fun presetReference(value: Double): ShaderLabPresetId {
    val reference = value.roundToInt().coerceIn(1, 20)
    return if (reference <= 10) ShaderLabPresetId.BuiltIn(reference) else ShaderLabPresetId.User(reference - 10)
  }

  private fun stepModeFromValue(value: Double): ShaderLabStepMode = when (value.roundToInt().coerceIn(1, 3)) {
    1 -> ShaderLabStepMode.FINE
    3 -> ShaderLabStepMode.COARSE
    else -> ShaderLabStepMode.NORMAL
  }

  private fun prettyGroup(group: ShaderLabGroup): String = group.name.replace('_', ' ')

  private fun horizontalRow(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
  }

  private fun largeButton(textValue: String): Button = Button(context).apply {
    text = textValue
    isAllCaps = false
    textSize = 14f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(Color.WHITE)
    background = roundedBackground(0xFF2A2E35.toInt(), 14f)
    isFocusable = false
  }

  private fun smallButton(textValue: String, onClick: (() -> Unit)?): Button = Button(context).apply {
    text = textValue
    isAllCaps = false
    textSize = 11f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(Color.WHITE)
    background = roundedBackground(0xFF292D34.toInt(), 12f)
    isFocusable = false
    if (onClick != null) setOnClickListener { onClick() }
  }

  private fun labelView(sizeSp: Float, color: Int, style: Int): TextView = TextView(context).apply {
    textSize = sizeSp
    setTextColor(color)
    typeface = Typeface.create(Typeface.DEFAULT, style)
    includeFontPadding = false
  }

  private fun linearMatchWrap(top: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

  private fun weightedButtonParams(
    start: Int = 0,
    end: Int = 0,
    height: Int = 54,
  ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(height), 1f).apply {
    marginStart = dp(start)
    marginEnd = dp(end)
  }

  private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    setStroke(dp(1), 0x335D6A79)
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

  private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

  private fun wrap(value: Int, size: Int): Int {
    if (size <= 0) return 0
    val mod = value % size
    return if (mod < 0) mod + size else mod
  }
}
