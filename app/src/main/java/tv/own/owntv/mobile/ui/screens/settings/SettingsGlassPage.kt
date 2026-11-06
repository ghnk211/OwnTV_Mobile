package tv.own.owntv.mobile.ui.screens.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.theme.BackgroundConfig
import tv.own.owntv.core.theme.BackgroundStyle
import tv.own.owntv.core.theme.GlassConfig
import tv.own.owntv.core.theme.PictureLook
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStageBackground
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageLabel
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSectionHead
import tv.own.owntv.mobile.ui.stage.StageSegmented
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageStepper
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText
import java.io.File

/**
 * Glass & background (the TV's GB page): the live preview, then BACKGROUND — Stage / Picture /
 * Plain, the picture, its look (Sharp / Soft / Dark), Darken and Blur while a picture shows, Accent light — and
 * GLASS: frosted bars and sheets, their opacity, and Reset. Every value is core's, so a TV backup brings the same
 * look; every change shows at once, on the preview and on the page itself.
 */
@Composable
fun SettingsGlassPage(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val background = vm.settings.backgroundConfig.pref(BackgroundConfig())
    val glass = vm.settings.glassConfig.pref(GlassConfig())
    val context = LocalContext.current
    val pic = background.style == BackgroundStyle.PICTURE
    val hasFile = background.imagePath.isNotBlank()
    val alphaPct = (glass.alpha * 100).toInt()

    // SAF, not a file browser: a phone has a picker, and the copy we keep survives the user later
    // deleting the original out of their gallery. A new picture starts on Picture · Soft (core).
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            vm.edit {
                val path = withContext(Dispatchers.IO) { ingestBackgroundImage(context, uri) }
                if (path != null) setNewBackgroundPicture(path)
            }
        }
    }
    val choosePicture = { pickImage.launch(arrayOf("image/*")) }
    // With a picture set, the row offers Change or Clear (the TV's chooser); without one it goes straight to the picker.
    var pictureMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (pictureMenu) {
        tv.own.owntv.mobile.ui.components.MobileBottomSheet(onDismissRequest = { pictureMenu = false }) {
            tv.own.owntv.mobile.ui.stage.StageSheetHeader(stringResource(R.string.settings_bg_picture), eyebrow = stringResource(R.string.settings_glass_bg_title))
            tv.own.owntv.mobile.ui.stage.StageMenuItem(stringResource(R.string.settings_bg_change), onClick = { pictureMenu = false; choosePicture() }, icon = MobileIcons.Folder)
            tv.own.owntv.mobile.ui.stage.StageMenuItem(stringResource(R.string.common_clear), onClick = {
                pictureMenu = false
                vm.edit {
                    setBgImagePath("")
                    // Picture with nothing to show would only draw the Stage colours under a "Picture" label.
                    if (pic) setBackgroundStyle(BackgroundStyle.STAGE)
                }
            }, icon = MobileIcons.Delete)
            Box(Modifier.padding(bottom = 6.dp))
        }
    }

    SettingsCrumb(pluralStringResource(R.plurals.settings_setting_count, 8, 8), SettingsGroup.APPEARANCE)
    SettingsStagePage(modifier) {
        item(key = "preview") { GlassPreview(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp)) }

        val bgRows = if (pic) 5 else 3
        item(key = "shd-bg") { StageSectionHead(stringResource(R.string.settings_bg_background), count = bgRows) }
        item(key = "bg") {
            val styles = listOf(BackgroundStyle.STAGE, BackgroundStyle.PICTURE, BackgroundStyle.PLAIN)
            StageSettingRow(
                title = stringResource(R.string.settings_bg_background),
                icon = MobileIcons.Layers,
                subtitle = stringResource(R.string.settings_line_bg_background),
                role = null,
            ) {
                StageSegmented(
                    options = listOf(stringResource(R.string.settings_bg_stage), stringResource(R.string.settings_bg_picture), stringResource(R.string.settings_bg_plain)),
                    selected = styles.indexOf(background.style),
                    onSelect = { i ->
                        vm.edit { setBackgroundStyle(styles[i]) }
                        // Picture with no picture yet goes straight to the picker (the TV's rule).
                        if (styles[i] == BackgroundStyle.PICTURE && !hasFile) choosePicture()
                    },
                )
            }
        }
        item(key = "picture") {
            StageSettingRow(
                title = stringResource(R.string.settings_bg_picture),
                icon = MobileIcons.Folder,
                subtitle = if (hasFile) {
                    stringResource(R.string.settings_bg_picture_from_phone, pictureName(background.imagePath) ?: stringResource(R.string.settings_bg_picture))
                } else {
                    stringResource(R.string.settings_line_bg_picture_none_mobile)
                },
                onClick = { if (hasFile) pictureMenu = true else choosePicture() },
            ) { StageRowValue(stringResource(if (hasFile) R.string.settings_bg_change else R.string.settings_bg_none), StageChevron.FORWARD) }
        }
        item(key = "look") {
            // Only a picture has a look; on Stage and Plain the row stays, saying what it is for, without a value.
            StageSettingRow(
                title = stringResource(R.string.settings_bg_look),
                icon = MobileIcons.AutoAwesome,
                subtitle = stringResource(R.string.settings_line_bg_look),
                role = null,
            ) {
                if (pic) {
                    StageSegmented(
                        options = listOf(stringResource(R.string.settings_bg_sharp), stringResource(R.string.settings_bg_soft), stringResource(R.string.settings_bg_dark)),
                        selected = PictureLook.entries.indexOf(background.look),
                        onSelect = { i -> vm.edit { setPictureLook(PictureLook.entries[i]) } },
                    )
                }
            }
        }
        if (pic) {
            item(key = "darken") {
                PercentRow(MobileIcons.Bedtime, R.string.settings_bg_darken, R.string.settings_line_bg_darken, background.dimPct, BackgroundConfig.DIM_MAX, 5) { vm.edit { setBackgroundDim(it) } }
            }
            item(key = "blur") {
                PercentRow(MobileIcons.AutoAwesome, R.string.settings_bg_blur, R.string.settings_line_bg_blur, background.blurPct, BackgroundConfig.BLUR_MAX, 10) { vm.edit { setBackgroundBlur(it) } }
            }
        }
        item(key = "shd-glass") { StageSectionHead(stringResource(R.string.settings_glass_section), count = 2) }
        item(key = "glass") {
            StageSettingRow(
                title = stringResource(R.string.settings_glass_section),
                icon = MobileIcons.Layers,
                subtitle = stringResource(R.string.settings_line_glass_switch_mobile),
                onClick = {
                    val bits = if (glass.enabled) 0 else GlassConfig.ON_BITS
                    vm.edit { setGlassScopeBitmask(bits) }
                },
                role = Role.Switch,
            ) { StageSwitch(glass.enabled) }
        }
        item(key = "opacity") {
            StageSettingRow(
                title = stringResource(R.string.settings_glass_opacity),
                icon = MobileIcons.Layers,
                subtitle = stringResource(R.string.settings_line_glass_opacity),
                role = null,
            ) {
                if (glass.enabled) {
                    StageStepper(
                        stringResource(R.string.settings_surface_transparency, alphaPct),
                        onMinus = { vm.edit { setGlassAlphaPercent((alphaPct - 5).coerceIn(20, 100)) } },
                        onPlus = { vm.edit { setGlassAlphaPercent((alphaPct + 5).coerceIn(20, 100)) } },
                        minusEnabled = alphaPct > 20,
                        plusEnabled = alphaPct < 100,
                    )
                } else {
                    StageRowValue(stringResource(R.string.common_off), plain = true)
                }
            }
        }
        item(key = "reset") {
            StageSettingRow(
                title = stringResource(R.string.common_reset),
                icon = MobileIcons.Refresh,
                subtitle = stringResource(
                    R.string.settings_line_glass_reset,
                    stringResource(R.string.common_percent, PHONE_GLASS_DEFAULT_PCT),
                ),
                onClick = { vm.edit { resetGlassAndBackground(PHONE_GLASS_DEFAULT_PCT) } },
            )
        }
    }
}

/** The phone's glass opacity for a new user and for Reset; the TV's is core's 50 %. */
internal const val PHONE_GLASS_DEFAULT_PCT = 51

/** Darken / Blur: "50%" in a stepper, [step] at a time within 0…[max]. */
@Composable
private fun PercentRow(icon: androidx.compose.ui.graphics.vector.ImageVector, titleRes: Int, lineRes: Int, value: Int, max: Int, step: Int, onSet: (Int) -> Unit) {
    StageSettingRow(title = stringResource(titleRes), icon = icon, subtitle = stringResource(lineRes), role = null) {
        StageStepper(
            stringResource(R.string.settings_surface_transparency, value),
            onMinus = { onSet((value - step).coerceIn(0, max)) },
            onPlus = { onSet((value + step).coerceIn(0, max)) },
            minusEnabled = value > 0,
            plusEnabled = value < max,
        )
    }
}

/**
 * The live preview: "Live TV" over the current background — the picture with its look, the Stage colours or
 * Plain — and a glass bottom bar, Live TV lit. It reads the same settings the app is drawn with, so it follows
 * every change on this page at once.
 */
@Composable
private fun GlassPreview(modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    val background = LocalStageBackground.current
    val accent = stageAccent.accent
    Box(modifier) {
        androidx.compose.foundation.layout.Column {
            StageLabel(stringResource(R.string.settings_glass_live_preview).uppercase(), Modifier.padding(start = 4.dp, bottom = 6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, palette.ink.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
            ) {
                if (background.showsPicture) {
                    // The preview is a fifth of the screen's height, so its blur is too.
                    AsyncImage(
                        model = File(background.imagePath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur((background.blurPct / 100f * 8f).dp),
                    )
                    val dim = background.dimPct / 100f
                    Box(Modifier.fillMaxSize().background(if (palette.isLight) Color(0xFFF4F7F5).copy(alpha = 0.55f + 0.45f * dim) else Color(0xFF05080A).copy(alpha = dim)))
                }
                Box(Modifier.fillMaxSize().stageBackground())
                Text(
                    stringResource(R.string.common_nav_live_tv),
                    style = stageText(15f, 800),
                    color = palette.text,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .fillMaxWidth()
                        .height(44.dp)
                        .stageGlass(RoundedCornerShape(16.dp)),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(MobileIcons.Home, MobileIcons.LiveTv, MobileIcons.Guide, MobileIcons.Movie, MobileIcons.Series).forEachIndexed { i, icon ->
                        StageIcon(icon, 18.dp, if (i == 1) accent else palette.navText)
                    }
                }
            }
        }
    }
}

/** The picked picture's own name, kept in the stored file's name after `__`; null for a picture stored before that. */
private fun pictureName(path: String): String? =
    File(path).name.substringAfter("__", "").substringBeforeLast(".img").ifBlank { null }

/**
 * Copy a picked image into app-private storage and return its path, or null if the copy failed.
 *
 * The picked Uri itself is not persisted: its permission grant does not survive a reboot, and the
 * user may delete the original. The folder is wiped first so backgrounds never accumulate — it is
 * the same folder, and the same rule, that the backup container carries the wallpaper in. The file's
 * own name rides after `__`, so the Picture row can say which picture it is.
 */
private fun ingestBackgroundImage(context: android.content.Context, uri: Uri): String? =
    runCatching {
        val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        // A fresh name every time, or Coil serves the previous bitmap from its path-keyed cache.
        val dest = File(dir, "background_${System.currentTimeMillis()}__$name.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dest.absolutePath
    }.getOrNull()
