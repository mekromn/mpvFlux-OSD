package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.PlayerButton
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.theme.controlColor
import dev.vivvvek.seeker.Segment
import org.koin.compose.koinInject

internal fun List<PlayerButton>.withShaderLabAccessButton(): List<PlayerButton> {
  if (PlayerButton.SHADER_LAB in this || PlayerButton.MORE_OPTIONS !in this) return this
  return flatMap { button ->
    if (button == PlayerButton.MORE_OPTIONS) listOf(PlayerButton.SHADER_LAB, button) else listOf(button)
  }
}

@Composable
fun RenderConfigurablePlayerButton(
  button: PlayerButton,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  modifier: Modifier = Modifier,
  buttonSize: Dp = 48.dp,
) {
  val shaderLabUi = koinInject<ShaderLabUiController>()
  val shaderLabBridge = koinInject<MpvShaderLabBridge>()
  val clickEvent = LocalPlayerButtonsClickEvent.current

  when (button) {
    PlayerButton.MORE_OPTIONS ->
      ControlsButton(
        icon = button.icon,
        onClick = {
          clickEvent()
          onOpenSheet(Sheets.More)
        },
        color = controlColor,
        modifier = modifier.size(buttonSize),
        shape = CircleShape,
      )

    PlayerButton.SHADER_LAB ->
      ControlsButton(
        icon = button.icon,
        onClick = {
          clickEvent()
          // The MPV wrapper normally attaches Shader Lab from
          // MPVView.observeProperties(). Device testing showed that the
          // renderer-parity AAR can reach playback while that lifecycle hook
          // is missed, leaving the Studio bound to the same Koin singleton but
          // with attached=false. Repair that boundary synchronously before the
          // first Studio frame. MpvShaderLabBridge.attach() already handles a
          // previous attachment by replacing its observer safely.
          if (!shaderLabUi.visible.value) {
            shaderLabBridge.attach()
          }
          shaderLabUi.toggle()
        },
        color = controlColor,
        modifier = modifier.size(buttonSize),
        shape = CircleShape,
      )

    else ->
      RenderPlayerButton(
        button = button,
        chapters = chapters,
        currentChapter = currentChapter,
        isSpeedNonOne = isSpeedNonOne,
        currentZoom = currentZoom,
        mediaTitle = mediaTitle,
        hideBackground = hideBackground,
        decoder = decoder,
        playbackSpeed = playbackSpeed,
        onBackPress = onBackPress,
        onOpenSheet = onOpenSheet,
        viewModel = viewModel,
        activity = activity,
        modifier = modifier,
        buttonSize = buttonSize,
      )
  }
}
