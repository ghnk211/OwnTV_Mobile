package tv.own.owntv.mobile.ui.setup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Getting a file the user picked out of the phone's storage and into the app's own.
 *
 * The document picker hands back a `content://` URI, which is a permission to read one file right
 * now — not a path, and not something that still works after a reboot. Core's playlist parser and
 * its backup reader both want a real file, so the picked bytes are copied in before either is asked
 * to look at anything.
 */

/** Opens the system document picker for a playlist. Any type: providers serve .m3u as text/plain,
 *  as octet-stream, and as a dozen invented types, and filtering by them hides real playlists. */
@Composable
fun rememberPlaylistFilePicker(onPicked: (Uri) -> Unit): () -> Unit = rememberPicker(onPicked)

/** The same picker for a backup container — a `.own`, or a `.json` from before 4.2. */
@Composable
fun rememberBackupFilePicker(onPicked: (Uri) -> Unit): () -> Unit = rememberPicker(onPicked)

@Composable
private fun rememberPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * The TVBox tab only accepts a `.json` config. File providers disagree on the MIME type of a JSON
 * document (`application/json`, `text/plain`, `application/octet-stream`…), so filtering by MIME in
 * the picker would hide perfectly good configs. Everything stays selectable, and the displayed name
 * is checked instead; a non-JSON pick reports back so the form can say what went wrong.
 */
@Composable
fun rememberJsonFilePicker(onPicked: (Uri) -> Unit, onRejected: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        when {
            uri == null -> Unit
            isJsonDocument(context, uri) -> onPicked(uri)
            else -> onRejected()
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

private fun isJsonDocument(context: Context, uri: Uri): Boolean =
    displayName(context, uri).substringAfterLast('.', "").equals("json", ignoreCase = true)

/**
 * Copies the picked document into [directory], keeping its own name so the file the user chose is
 * still recognisable later. Returns null if it could not be read.
 *
 * A playlist goes to `filesDir`, because the source keeps pointing at it and has to survive a
 * reboot; a backup goes to `cacheDir`, because it is read once and never wanted again.
 */
suspend fun copyPickedFile(context: Context, uri: Uri, directory: File): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val target = directory.apply { mkdirs() }.resolve(displayName(context, uri))
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target
        }.getOrNull()
    }

private fun displayName(context: Context, uri: Uri): String {
    val name = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    // Some providers report no name at all; the extension is what the backup reader checks, so a
    // nameless pick still needs a plausible one.
    return name?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "picked"
}
