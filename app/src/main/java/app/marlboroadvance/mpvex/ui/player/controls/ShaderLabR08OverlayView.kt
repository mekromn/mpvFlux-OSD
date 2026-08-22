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
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlKind
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlSpec
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabGroup
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabStepMode
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabAdjustDirection
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommand
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandResult
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
 * R08 resident-parameter test surface.
 *
 * This overlay intentionally does not drive the legacy Lua workstation's
 * shader-file rewrite/A-B swap path. All ordinary tuning enters through
 * [ShaderLabCommandApi], so shader/master controls publish the resident
 * vo=gpu glsl-shader-opts set and MPV controls remain direct MPV properties.
 *
 * The control list is generated from [ShaderLabControlCatalog]. That keeps
 * this test surface in lock-step with the resident backend instead of growing
 * a second hand-maintained parameter list.
 */
class ShaderLabR08OverlayView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), KoinComponent {
  private val bridge: MpvShaderLabBridge by inject()
  private val commandApi: ShaderLabCommandApi by inject()

  private val handler = Handler(Looper.getMainLooper())
  private var attachmentScope: CoroutineScope? = null
  private var stateJob: Job? = null
  private var backendState = ShaderLabBackendState()

  private val testableControls: List<ShaderLabControlSpec> =
    ShaderLabControlCatalog.controls.filter { spec ->
      spec.kind == ShaderLabControlKind.SHADER ||
        spec.kind == ShaderLabControlKind.MPV_PROPERTY ||
        spec.id == ShaderLabControlId.LUMA_MASTER ||
        spec.id == ShaderLabControlId.CHROMA_MASTER
    }

  private val groups: List<ShaderLabGroup> =
    ShaderLabControlCatalog.groupOrder.filter { group ->
      testableControls.any { it.group == group }
    }

  private var groupIndex = 0
  private var controlIndex = 0
  private var selectedControlId: ShaderLabControlId? = null
  private var editMode = false
  private var stepMode = ShaderLabStepMode.NORMAL
  private var expanded = false
  private var previewHeld = false

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
          render()
        }
      }
  }

  override fun onDetachedFromWindow() {
    stopBrowseRepeat()
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
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !editMode) {
          moveGroup(-1)
        }
        return true
      }

      KeyEvent.KEYCODE_DPAD_DOWN -> {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !editMode) {
          moveGroup(1)
        }
        return true
      }

      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_NUMPAD_ENTER,
      -> {
        if (event.action == KeyEvent.ACTION_UP) toggleEditMode()
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
    addView(
      labButton,
      LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.CENTER),
    )

    panel =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = roundedBackground(0xEE101216.toInt(), 20f)
        elevation = dp(10).toFloat()
        visibility = View.GONE
      }
    addView(
      panel,
      LayoutParams(dp(372), LayoutParams.WRAP_CONTENT, Gravity.CENTER),
    )

    panel.addView(buildHeaderRow())

    statusText = labelView(12f, 0xFFB8C3D1.toInt(), Typeface.NORMAL)
    statusText.gravity = Gravity.CENTER
    panel.addView(statusText, linearMatchWrap(top = 4))

    panel.addView(buildGroupRow(), linearMatchWrap(top = 10))

    parameterText = labelView(18f, Color.WHITE, Typeface.BOLD)
    parameterText.gravity = Gravity.CENTER
    parameterText.maxLines = 2
    panel.addView(parameterText, linearMatchWrap(top = 12))

    valueText = labelView(30f, Color.WHITE, Typeface.BOLD)
    valueText.gravity = Gravity.CENTER
    panel.addView(valueText, linearMatchWrap(top = 2))

    modeText = labelView(12f, 0xFFB8C3D1.toInt(), Typeface.BOLD)
    modeText.gravity = Gravity.CENTER
    panel.addView(modeText, linearMatchWrap(top = 2))

    panel.addView(buildThreeButtonRow(), linearMatchWrap(top = 10))
    panel.addView(buildQuickActionRow(), linearMatchWrap(top = 10))

    val hint = labelView(11f, 0xFF929EAD.toInt(), Typeface.NORMAL)
    hint.text = "Browse: ◀/▶   Edit: CENTER   Groups: ↑/↓   Hold repeat = browse only"
    hint.gravity = Gravity.CENTER
    panel.addView(hint, linearMatchWrap(top = 8))
  }

  private fun buildHeaderRow(): View {
    val row = horizontalRow()

    val title = labelView(16f, Color.WHITE, Typeface.BOLD).apply {
      text = "Resident Shader Lab"
      gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))

    val close = smallButton("✕") { setExpanded(false) }
    row.addView(close, LinearLayout.LayoutParams(dp(46), dp(40)))
    return row
  }

  private fun buildGroupRow(): View {
    val row = horizontalRow()
    val previous = smallButton("‹") { moveGroup(-1) }
    val next = smallButton("›") { moveGroup(1) }

    groupText = labelView(14f, 0xFFE0E6EE.toInt(), Typeface.BOLD).apply {
      gravity = Gravity.CENTER
      setPadding(dp(4), 0, dp(4), 0)
      setOnClickListener { moveGroup(1) }
    }

    row.addView(previous, LinearLayout.LayoutParams(dp(48), dp(40)))
    row.addView(groupText, LinearLayout.LayoutParams(0, dp(40), 1f))
    row.addView(next, LinearLayout.LayoutParams(dp(48), dp(40)))
    return row
  }

  private fun buildThreeButtonRow(): View {
    val row = horizontalRow()

    val left = largeButton("◀")
    centerButton = largeButton("SELECT")
    val right = largeButton("▶")

    installDirectionalTouch(left, -1)
    installDirectionalTouch(right, 1)
    centerButton.setOnClickListener { toggleEditMode() }

    row.addView(left, weightedButtonParams(end = 5))
    row.addView(centerButton, weightedButtonParams(start = 5, end = 5))
    row.addView(right, weightedButtonParams(start = 5))
    return row
  }

  private fun buildQuickActionRow(): View {
    val row = horizontalRow()

    stepButton = smallButton("STEP") { cycleStepMode() }
    bypassButton = smallButton("BYPASS") {
      execute(ShaderLabCommand.ToggleBypass)
    }
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
    labButton.visibility = if (show) View.GONE else View.VISIBLE
    panel.visibility = if (show) View.VISIBLE else View.GONE
    if (show) {
      requestFocus()
    } else {
      clearFocus()
    }
    render()
  }

  private fun currentGroup(): ShaderLabGroup = groups[groupIndex.coerceIn(groups.indices)]

  private fun currentGroupControls(): List<ShaderLabControlSpec> =
    testableControls.filter { it.group == currentGroup() }

  private fun currentSpec(): ShaderLabControlSpec {
    val controls = currentGroupControls()
    check(controls.isNotEmpty()) { "Shader Lab group unexpectedly has no controls: ${currentGroup()}" }

    val selectedIndex = selectedControlId?.let { id -> controls.indexOfFirst { it.id == id } } ?: -1
    if (selectedIndex >= 0) {
      controlIndex = selectedIndex
    } else if (controlIndex !in controls.indices) {
      controlIndex = 0
    }

    return controls[controlIndex].also { selectedControlId = it.id }
  }

  private fun moveControl(direction: Int) {
    val controls = currentGroupControls()
    if (controls.isEmpty()) return
    val currentId = currentSpec().id
    val current = controls.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
    controlIndex = wrap(current + direction, controls.size)
    selectedControlId = controls[controlIndex].id
    commandApi.execute(ShaderLabCommand.SelectControl(selectedControlId!!))
    render()
  }

  private fun moveGroup(direction: Int) {
    if (groups.isEmpty() || editMode) return
    groupIndex = wrap(groupIndex + direction, groups.size)
    controlIndex = 0
    selectedControlId = null
    commandApi.execute(ShaderLabCommand.SelectGroup(currentGroup()))
    commandApi.execute(ShaderLabCommand.SelectControl(currentSpec().id))
    render()
  }

  private fun toggleEditMode() {
    editMode = !editMode
    stopBrowseRepeat()
    commandApi.execute(ShaderLabCommand.SelectControl(currentSpec().id))
    render()
  }

  private fun handleDirection(direction: Int) {
    if (editMode) {
      adjustCurrent(direction)
    } else {
      moveControl(direction)
    }
  }

  private fun adjustCurrent(direction: Int) {
    val command =
      ShaderLabCommand.Adjust(
        control = currentSpec().id,
        direction =
          if (direction < 0) ShaderLabAdjustDirection.DECREASE
          else ShaderLabAdjustDirection.INCREASE,
        stepMode = stepMode,
      )
    execute(command)
  }

  private fun cycleStepMode() {
    stepMode =
      when (stepMode) {
        ShaderLabStepMode.FINE -> ShaderLabStepMode.NORMAL
        ShaderLabStepMode.NORMAL -> ShaderLabStepMode.COARSE
        ShaderLabStepMode.COARSE -> ShaderLabStepMode.FINE
      }
    render()
  }

  /**
   * Touch semantics deliberately separate browse-repeat from adjustment.
   * Browse starts immediately and may repeat while held. Edit mode performs
   * exactly one adjustment on release, regardless of hold duration.
   *
   * Direction buttons intentionally use only this touch path. A second Android
   * click listener can synthesize another navigation action after ACTION_UP on
   * some devices, which makes a single tap skip a control.
   */
  private fun installDirectionalTouch(button: Button, direction: Int) {
    button.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          if (editMode) {
            stopBrowseRepeat()
          } else {
            moveControl(direction)
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
    val runnable =
      object : Runnable {
        override fun run() {
          if (editMode || !expanded) return
          moveControl(direction)
          browseRepeatCount += 1
          val delay =
            when {
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
            execute(ShaderLabCommand.PreviewOriginalStart)
          }
          true
        }

        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_OUTSIDE,
        -> {
          if (previewHeld) {
            previewHeld = false
            execute(ShaderLabCommand.PreviewOriginalEnd)
          }
          true
        }

        else -> true
      }
    }
  }

  private fun execute(command: ShaderLabCommand) {
    when (val result = commandApi.execute(command)) {
      is ShaderLabCommandResult.Applied -> Unit
      is ShaderLabCommandResult.Rejected -> showTransientError(result.reason)
      is ShaderLabCommandResult.Failed -> showTransientError(result.reason)
    }
    render()
  }

  private fun showTransientError(message: String) {
    statusText.text = "R08 ERROR • $message"
    handler.postDelayed({ render() }, 1800L)
  }

  private fun render() {
    if (!::statusText.isInitialized || groups.isEmpty()) return

    val spec = currentSpec()
    val rawValue = backendState.values[spec.id] ?: spec.defaultValue
    val value = spec.clamp(rawValue)

    val connection =
      when {
        backendState.ready -> "READY"
        backendState.connected -> "SYNCING"
        else -> "OFFLINE"
      }
    val source = backendState.sourceKind.name.replace('_', '-')
    val path = if (spec.kind == ShaderLabControlKind.MPV_PROPERTY) "MPV" else "RESIDENT"
    statusText.text = "R08 • $connection • $source • $path"

    groupText.text = prettyGroup(currentGroup())
    parameterText.text = spec.label
    valueText.text = formatValue(spec, value)

    modeText.text =
      if (editMode) {
        "EDIT • ${stepMode.name} • ${stepDescription(spec)}"
      } else {
        "BROWSE • ${controlIndex + 1}/${currentGroupControls().size}"
      }
    centerButton.text = if (editMode) "DONE" else "SELECT"
    centerButton.background =
      roundedBackground(
        if (editMode) 0xFF335C99.toInt() else 0xFF2A2E35.toInt(),
        14f,
      )

    stepButton.text = stepMode.name
    bypassButton.text = if (backendState.bypassed) "BYPASSED" else "BYPASS"
    previewButton.text = if (previewHeld || backendState.previewOriginal) "ORIGINAL" else "HOLD ORIGINAL"
  }

  private fun formatValue(spec: ShaderLabControlSpec, value: Double): String {
    if (spec.choices.isNotEmpty()) {
      val index = value.roundToInt().coerceIn(0, spec.choices.lastIndex)
      return "${spec.choices[index]}  (${spec.format(value)})"
    }
    return spec.format(value)
  }

  private fun stepDescription(spec: ShaderLabControlSpec): String =
    "±${spec.step(stepMode)}"

  private fun prettyGroup(group: ShaderLabGroup): String =
    group.name.replace('_', ' ')

  private fun horizontalRow(): LinearLayout =
    LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }

  private fun largeButton(textValue: String): Button =
    Button(context).apply {
      text = textValue
      isAllCaps = false
      textSize = 14f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      background = roundedBackground(0xFF2A2E35.toInt(), 14f)
      isFocusable = false
    }

  private fun smallButton(
    textValue: String,
    onClick: (() -> Unit)?,
  ): Button =
    Button(context).apply {
      text = textValue
      isAllCaps = false
      textSize = 11f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      background = roundedBackground(0xFF292D34.toInt(), 12f)
      isFocusable = false
      if (onClick != null) setOnClickListener { onClick() }
    }

  private fun labelView(
    sizeSp: Float,
    color: Int,
    style: Int,
  ): TextView =
    TextView(context).apply {
      textSize = sizeSp
      setTextColor(color)
      typeface = Typeface.create(Typeface.DEFAULT, style)
      includeFontPadding = false
    }

  private fun linearMatchWrap(top: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(top)
    }

  private fun weightedButtonParams(
    start: Int = 0,
    end: Int = 0,
    height: Int = 54,
  ): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, dp(height), 1f).apply {
      marginStart = dp(start)
      marginEnd = dp(end)
    }

  private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable =
    GradientDrawable().apply {
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
