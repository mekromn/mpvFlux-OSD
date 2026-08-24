package app.marlboroadvance.mpvex.repository.shaderlab.command

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabActionId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlRelationship
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabGroup
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabPresetId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabStepMode

enum class ShaderLabAdjustDirection(val multiplier: Double) {
  DECREASE(-1.0),
  INCREASE(1.0),
}

enum class ShaderLabDiagnosticView(val backendValue: Double) {
  OFF(0.0),
  GAMUT(1.0),
  LUMA(2.0),
  BOTH(3.0),
}

/**
 * Input-neutral Shader Lab intent surface.
 *
 * Touch, TV/D-pad, presets, tests, and later ViewModel code should create these
 * commands instead of talking to MPV/Lua directly. R07 supplies the concrete
 * MPV backend; this layer deliberately has no Android UI or Compose dependency.
 */
sealed interface ShaderLabCommand {
  data class SetValue(
    val control: ShaderLabControlId,
    val value: Double,
  ) : ShaderLabCommand

  data class Adjust(
    val control: ShaderLabControlId,
    val direction: ShaderLabAdjustDirection,
    val stepMode: ShaderLabStepMode,
  ) : ShaderLabCommand

  data class SelectGroup(val group: ShaderLabGroup) : ShaderLabCommand

  data class SelectControl(val control: ShaderLabControlId) : ShaderLabCommand

  data object ToggleBypass : ShaderLabCommand

  data object PreviewOriginalStart : ShaderLabCommand

  data object PreviewOriginalEnd : ShaderLabCommand

  /** Legacy workstation fallback only; native UI should prefer start/end. */
  data object TogglePreviewOriginalFallback : ShaderLabCommand

  data object RevertVideoStart : ShaderLabCommand

  data object ResetAll : ShaderLabCommand

  data class SaveUserPreset(val preset: ShaderLabPresetId.User) : ShaderLabCommand

  data class LoadUserPreset(val preset: ShaderLabPresetId.User) : ShaderLabCommand

  data class ClearUserPreset(val preset: ShaderLabPresetId.User) : ShaderLabCommand

  data class LoadBuiltInPreset(val preset: ShaderLabPresetId.BuiltIn) : ShaderLabCommand

  data class Morph(
    val from: ShaderLabPresetId,
    val to: ShaderLabPresetId,
    val amount: Double,
  ) : ShaderLabCommand

  data class SetDiagnosticView(val mode: ShaderLabDiagnosticView) : ShaderLabCommand

  data object SaveState : ShaderLabCommand

  data object LoadState : ShaderLabCommand
}

/**
 * Runtime boundary consumed by [ShaderLabCommandApi].
 *
 * The interface is intentionally semantic: no MPV command names, script-message
 * strings, Compose types, key events, or pointer events cross this boundary.
 */
interface ShaderLabCommandBackend {
  fun snapshotValues(): Map<ShaderLabControlId, Double>

  fun setValues(values: Map<ShaderLabControlId, Double>)

  fun toggleBypass()

  fun setPreviewOriginal(active: Boolean)

  fun togglePreviewOriginalFallback()

  fun revertVideoStart()

  fun resetAll()

  fun saveUserPreset(preset: ShaderLabPresetId.User)

  fun loadUserPreset(preset: ShaderLabPresetId.User)

  fun clearUserPreset(preset: ShaderLabPresetId.User)

  fun loadBuiltInPreset(preset: ShaderLabPresetId.BuiltIn)

  fun morph(
    from: ShaderLabPresetId,
    to: ShaderLabPresetId,
    amount: Double,
  )

  fun saveState()

  fun loadState()
}

sealed interface ShaderLabCommandEffect {
  data class ValuesChanged(
    val values: Map<ShaderLabControlId, Double>,
  ) : ShaderLabCommandEffect

  data class GroupSelected(val group: ShaderLabGroup) : ShaderLabCommandEffect

  data class ControlSelected(
    val control: ShaderLabControlId,
    val group: ShaderLabGroup,
  ) : ShaderLabCommandEffect

  data object BypassToggled : ShaderLabCommandEffect

  data class PreviewOriginalChanged(val active: Boolean) : ShaderLabCommandEffect

  data object PreviewOriginalFallbackToggled : ShaderLabCommandEffect

  data object VideoStartReverted : ShaderLabCommandEffect

  data object AllReset : ShaderLabCommandEffect

  data class UserPresetSaved(val preset: ShaderLabPresetId.User) : ShaderLabCommandEffect

  data class UserPresetLoaded(val preset: ShaderLabPresetId.User) : ShaderLabCommandEffect

  data class UserPresetCleared(val preset: ShaderLabPresetId.User) : ShaderLabCommandEffect

  data class BuiltInPresetLoaded(val preset: ShaderLabPresetId.BuiltIn) : ShaderLabCommandEffect

  data class MorphApplied(
    val from: ShaderLabPresetId,
    val to: ShaderLabPresetId,
    val amount: Double,
  ) : ShaderLabCommandEffect

  data class DiagnosticViewChanged(val mode: ShaderLabDiagnosticView) : ShaderLabCommandEffect

  data object StateSaved : ShaderLabCommandEffect

  data object StateLoaded : ShaderLabCommandEffect
}

sealed interface ShaderLabCommandResult {
  data class Applied(
    val command: ShaderLabCommand,
    val effect: ShaderLabCommandEffect,
  ) : ShaderLabCommandResult

  data class Rejected(
    val command: ShaderLabCommand,
    val reason: String,
  ) : ShaderLabCommandResult

  data class Failed(
    val command: ShaderLabCommand,
    val reason: String,
    val exceptionType: String,
  ) : ShaderLabCommandResult
}

/**
 * Executes semantic commands against a transport-neutral backend.
 *
 * Value commands are clamped and relationship-normalized through the R05
 * authoritative catalog before reaching the backend. Ordered-pair normalization
 * only writes the addressed control and any directly related pair member, so a
 * single command cannot silently repair unrelated controls.
 */
class ShaderLabCommandApi(
  private val backend: ShaderLabCommandBackend,
) {
  fun execute(command: ShaderLabCommand): ShaderLabCommandResult =
    try {
      when (command) {
        is ShaderLabCommand.SetValue -> applyValue(command, command.control, command.value)
        is ShaderLabCommand.Adjust -> adjust(command)
        is ShaderLabCommand.SelectGroup -> applied(
          command,
          ShaderLabCommandEffect.GroupSelected(command.group),
        )
        is ShaderLabCommand.SelectControl -> applied(
          command,
          ShaderLabCommandEffect.ControlSelected(
            control = command.control,
            group = ShaderLabControlCatalog.spec(command.control).group,
          ),
        )
        ShaderLabCommand.ToggleBypass -> {
          backend.toggleBypass()
          applied(command, ShaderLabCommandEffect.BypassToggled)
        }
        ShaderLabCommand.PreviewOriginalStart -> {
          backend.setPreviewOriginal(true)
          applied(command, ShaderLabCommandEffect.PreviewOriginalChanged(true))
        }
        ShaderLabCommand.PreviewOriginalEnd -> {
          backend.setPreviewOriginal(false)
          applied(command, ShaderLabCommandEffect.PreviewOriginalChanged(false))
        }
        ShaderLabCommand.TogglePreviewOriginalFallback -> {
          backend.togglePreviewOriginalFallback()
          applied(command, ShaderLabCommandEffect.PreviewOriginalFallbackToggled)
        }
        ShaderLabCommand.RevertVideoStart -> {
          backend.revertVideoStart()
          applied(command, ShaderLabCommandEffect.VideoStartReverted)
        }
        ShaderLabCommand.ResetAll -> {
          backend.resetAll()
          applied(command, ShaderLabCommandEffect.AllReset)
        }
        is ShaderLabCommand.SaveUserPreset -> {
          backend.saveUserPreset(command.preset)
          applied(command, ShaderLabCommandEffect.UserPresetSaved(command.preset))
        }
        is ShaderLabCommand.LoadUserPreset -> {
          backend.loadUserPreset(command.preset)
          applied(command, ShaderLabCommandEffect.UserPresetLoaded(command.preset))
        }
        is ShaderLabCommand.ClearUserPreset -> {
          backend.clearUserPreset(command.preset)
          applied(command, ShaderLabCommandEffect.UserPresetCleared(command.preset))
        }
        is ShaderLabCommand.LoadBuiltInPreset -> {
          backend.loadBuiltInPreset(command.preset)
          applied(command, ShaderLabCommandEffect.BuiltInPresetLoaded(command.preset))
        }
        is ShaderLabCommand.Morph -> morph(command)
        is ShaderLabCommand.SetDiagnosticView -> setDiagnosticView(command)
        ShaderLabCommand.SaveState -> {
          backend.saveState()
          applied(command, ShaderLabCommandEffect.StateSaved)
        }
        ShaderLabCommand.LoadState -> {
          backend.loadState()
          applied(command, ShaderLabCommandEffect.StateLoaded)
        }
      }
    } catch (error: Throwable) {
      ShaderLabCommandResult.Failed(
        command = command,
        reason = error.message ?: "Shader Lab backend command failed",
        exceptionType = error::class.java.name,
      )
    }

  private fun adjust(command: ShaderLabCommand.Adjust): ShaderLabCommandResult {
    val spec = ShaderLabControlCatalog.spec(command.control)
    val current = currentValues().getValue(command.control)
    val requested = current + command.direction.multiplier * spec.step(command.stepMode)
    return applyValue(command, command.control, requested)
  }

  private fun setDiagnosticView(
    command: ShaderLabCommand.SetDiagnosticView,
  ): ShaderLabCommandResult {
    val result = applyValue(
      command = command,
      control = ShaderLabControlId.DEBUG_VIEW,
      requestedValue = command.mode.backendValue,
    )
    return if (result is ShaderLabCommandResult.Applied) {
      result.copy(effect = ShaderLabCommandEffect.DiagnosticViewChanged(command.mode))
    } else {
      result
    }
  }

  private fun morph(command: ShaderLabCommand.Morph): ShaderLabCommandResult {
    if (!command.amount.isFinite()) {
      return ShaderLabCommandResult.Rejected(command, "Morph amount must be finite")
    }
    if (!command.from.isMorphable() || !command.to.isMorphable()) {
      return ShaderLabCommandResult.Rejected(
        command,
        "Morph endpoints must be user or built-in presets",
      )
    }

    val amount = command.amount.coerceIn(0.0, 1.0)
    backend.morph(command.from, command.to, amount)
    return applied(
      command,
      ShaderLabCommandEffect.MorphApplied(command.from, command.to, amount),
    )
  }

  private fun applyValue(
    command: ShaderLabCommand,
    control: ShaderLabControlId,
    requestedValue: Double,
  ): ShaderLabCommandResult {
    if (!requestedValue.isFinite()) {
      return ShaderLabCommandResult.Rejected(command, "Control value must be finite")
    }

    val before = currentValues()
    val proposed = before.toMutableMap().apply { put(control, requestedValue) }
    val normalized = ShaderLabControlCatalog.normalizeValues(proposed, changedId = control)
    val relevantIds = linkedSetOf(control).apply {
      ShaderLabControlCatalog.relationships
        .filterIsInstance<ShaderLabControlRelationship.OrderedPair>()
        .forEach { relationship ->
          when (control) {
            relationship.lower -> add(relationship.upper)
            relationship.upper -> add(relationship.lower)
            else -> Unit
          }
        }
    }

    val writes = relevantIds
      .mapNotNull { id ->
        val value = normalized.getValue(id)
        if (value == before.getValue(id)) null else id to value
      }
      .toMap(linkedMapOf())

    if (writes.isNotEmpty()) {
      backend.setValues(writes)
    }
    return applied(command, ShaderLabCommandEffect.ValuesChanged(writes))
  }

  private fun currentValues(): Map<ShaderLabControlId, Double> {
    val values = ShaderLabControlCatalog.defaults().toMutableMap()
    backend.snapshotValues().forEach { (id, value) ->
      if (value.isFinite()) {
        values[id] = ShaderLabControlCatalog.spec(id).clamp(value)
      }
    }
    return values
  }

  private fun applied(
    command: ShaderLabCommand,
    effect: ShaderLabCommandEffect,
  ): ShaderLabCommandResult.Applied = ShaderLabCommandResult.Applied(command, effect)
}

fun ShaderLabCommand.requiresConfirmation(): Boolean {
  val actionId = when (this) {
    ShaderLabCommand.ToggleBypass -> ShaderLabActionId.BYPASS
    ShaderLabCommand.TogglePreviewOriginalFallback -> ShaderLabActionId.PREVIEW_TOGGLE_FALLBACK
    is ShaderLabCommand.LoadUserPreset -> ShaderLabActionId.LOAD_USER
    is ShaderLabCommand.SaveUserPreset -> ShaderLabActionId.SAVE_USER
    is ShaderLabCommand.ClearUserPreset -> ShaderLabActionId.CLEAR_USER
    is ShaderLabCommand.LoadBuiltInPreset -> ShaderLabActionId.LOAD_BUILTIN
    ShaderLabCommand.RevertVideoStart -> ShaderLabActionId.REVERT_VIDEO_START
    ShaderLabCommand.ResetAll -> ShaderLabActionId.RESET_ALL
    ShaderLabCommand.SaveState -> ShaderLabActionId.SAVE_STATE
    ShaderLabCommand.LoadState -> ShaderLabActionId.LOAD_STATE
    else -> null
  } ?: return false

  return ShaderLabControlCatalog.actions.first { it.id == actionId }.destructive
}

private fun ShaderLabPresetId.isMorphable(): Boolean =
  this is ShaderLabPresetId.User || this is ShaderLabPresetId.BuiltIn
