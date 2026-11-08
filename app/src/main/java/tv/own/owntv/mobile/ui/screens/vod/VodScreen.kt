package tv.own.owntv.mobile.ui.screens.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.stageText

/** On demand, before it ships: the place the page will take, and nothing it pretends to do yet. */
@Composable
fun VodScreen(modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    Column(
        modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StageIcon(MobileIcons.Movie, 34.dp, palette.dim)
        Text(
            stringResource(R.string.mobile_vod_placeholder_title),
            style = stageText(17f, 800),
            color = palette.text,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            stringResource(R.string.mobile_vod_placeholder_body),
            style = stageText(12.5f, 500, lineHeight = 1.45f),
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
