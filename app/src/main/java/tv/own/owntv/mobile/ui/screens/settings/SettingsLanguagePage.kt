package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.i18n.AppLocales
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageRadio
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.util.Locale

/**
 * The three languages this build ships strings for, each written in its own name.
 *
 * The list is the app's whitelist, not core's catalogue: an APK packaged in three locales cannot serve a
 * fourth, so offering one would only give a language that reads as English wherever it is not translated.
 * The "Help translate" wall and the coverage chips left with it — a percentage of a language that is not
 * in the APK is not information, and both contribution links pointed at a catalogue of 45. Search stays:
 * three endonyms in three scripts are still quicker to type than to scan for.
 */
@Composable
fun SettingsLanguagePage(
    modifier: Modifier = Modifier,
    localeStore: LocaleStore = koinInject(),
) {
    // Normalised, so a stored tag outside the whitelist ticks the row it actually resolves to instead of
    // matching nothing.
    val current = AppLocales.normalize(localeStore.currentTag.pref(""))
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    val rows = remember { AppLocales.rows.sortedBy { it.englishName.lowercase(Locale.ROOT) } }
    val needle = query.trim()
    val matches = remember(needle) { rows.filter { it.matches(needle) } }
    val systemLabel = stringResource(R.string.settings_language_system_default)
    val systemDescription = stringResource(R.string.settings_language_system_default_description)
    val showSystem = needle.isEmpty() ||
        systemLabel.contains(needle, true) || systemDescription.contains(needle, true)

    fun choose(tag: String) = scope.launch { runCatching { localeStore.set(tag) } }

    SettingsPage(modifier) {
        item(key = "search") {
            // The Settings search's field, with its ✕.
            StageSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.settings_language_search_hint),
                onClear = { query = "" },
                clearLabel = stringResource(R.string.common_clear),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
            )
        }

        settingsGroup(key = "languages") {
            if (showSystem) {
                LanguageRow(
                    title = systemLabel,
                    subtitle = systemDescription,
                    selected = current.isEmpty(),
                    onClick = { choose(SupportedLocales.SYSTEM_DEFAULT_TAG) },
                )
            }
            matches.forEach { locale ->
                LanguageRow(
                    title = locale.endonym,
                    subtitle = locale.englishName,
                    selected = current == locale.languageTag,
                    onClick = { choose(locale.languageTag) },
                )
            }
            if (!showSystem && matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_settings_match, needle),
                    style = stageText(13f, 500),
                    color = LocalStagePalette.current.dim,
                    modifier = Modifier.padding(MobileDimens.ListRowPaddingH),
                )
            }
        }
    }
}

/** Endonym, English name or tag — whichever the user happens to think of the language by. */
private fun SupportedLocale.matches(needle: String): Boolean =
    needle.isEmpty() ||
        endonym.contains(needle, true) ||
        englishName.contains(needle, true) ||
        languageTag.contains(needle, true)

@Composable
private fun LanguageRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // A choice like every other (the choice sheet's radio).
    StageSettingRow(title = title, subtitle = subtitle, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StageRadio(selected)
        }
    }
}
