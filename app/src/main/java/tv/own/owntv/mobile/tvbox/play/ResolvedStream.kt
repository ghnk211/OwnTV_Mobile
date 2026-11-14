package tv.own.owntv.mobile.tvbox.play

/**
 * The lightweight result of resolving one stream: a fresh URL and the headers it needs.
 *
 * Deliberately not [ResolvedPlay]: a download retry has no detail-page context (lines, title,
 * poster, category), and must not fabricate one.
 */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
)