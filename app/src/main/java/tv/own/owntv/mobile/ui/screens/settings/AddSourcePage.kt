package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.setup.SetupEntry
import tv.own.owntv.mobile.ui.setup.SetupViewModel
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageFx
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Settings › Playlists › Add a source, the TV wizard's own choices as cards: Remote (fill the form in a
 * browser on another device), Type it here, Import (a backup file). Remote opens the companion server;
 * what the other device sends fills the form, and the user still presses Start import — as on the TV.
 * There is no "another profile's playlists" card: one profile now holds everything.
 */
@Composable
fun AddSourcePage(onSetup: (SetupEntry) -> Unit, modifier: Modifier = Modifier, vm: SetupViewModel = koinViewModel()) {
    var remote by remember { mutableStateOf(false) }

    SettingsStagePage(modifier) {
        item(key = "remote") {
            SourceCard(MobileIcons.Translate, stringResource(R.string.setup_from_phone), stringResource(R.string.setup_phone_card_line)) {
                vm.consumeRemotePayload()
                vm.startRemote()
                remote = true
            }
        }
        item(key = "type") {
            SourceCard(MobileIcons.Edit, stringResource(R.string.settings_add_type_here), stringResource(R.string.settings_line_add_type_here_mobile)) {
                vm.consumeRemotePayload()
                onSetup(SetupEntry.FORM)
            }
        }
        item(key = "import") {
            SourceCard(MobileIcons.Folder, stringResource(R.string.setup_import), stringResource(R.string.setup_restore_backup_file)) {
                onSetup(SetupEntry.IMPORT)
            }
        }
    }

    if (remote) RemoteSourceSheet(vm, onReceived = { remote = false; onSetup(SetupEntry.FORM) }, onDismiss = { remote = false })
}

/**
 * Remote, open (from here or the wizard's Add a playlist step): the companion server's address and code. A submission stops the
 * server and [onReceived] opens the form it fills in. The caller starts the server as it opens this.
 */
@Composable
internal fun RemoteSourceSheet(vm: SetupViewModel, onReceived: () -> Unit, onDismiss: () -> Unit) {
    val state by vm.remoteState.collectAsStateWithLifecycle()
    val payload by vm.remotePayload.collectAsStateWithLifecycle()
    LaunchedEffect(payload) {
        if (payload != null) {
            vm.stopRemote()
            onReceived()
        }
    }
    RemoteSheet(
        title = stringResource(R.string.setup_from_phone),
        body = stringResource(R.string.settings_help_add_from_phone_mobile),
        state = state,
        preparing = false,
        onRestart = { vm.startRemote() },
        onDismiss = { vm.stopRemote(); onDismiss() },
    )
}

/** `.card`: 18 dp corners on ink 4.5%, the icon, the name (14.5 / 800) and its line; pressed takes the fx look. */
@Composable
private fun SourceCard(icon: ImageVector, title: String, line: String, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .fillMaxWidth()
            .then(if (pressed) Modifier.stageFx(18.dp) else Modifier)
            .clip(shape)
            .background(palette.ink.copy(alpha = 0.045f))
            .stageClickable(interaction, onClick, null)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageIcon(icon, 22.dp, palette.text)
        Column(Modifier.weight(1f)) {
            Text(title, style = stageText(14.5f, 800), color = palette.text)
            Text(line, style = stageText(11.5f, 500, lineHeight = 1.3f), color = palette.muted)
        }
    }
}
