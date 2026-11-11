package tv.own.owntv.core.categorylock

import tv.own.owntv.core.live.LiveKey

/**
 * A menu action — or a plain tap — on a sealed category. Hiding, moving or restoring it would change
 * what the lock guards, and playing from it would show what the lock hides, so the PIN is asked first
 * and the action runs once the PIN opens the category.
 */
enum class SealedCategoryAction {
    HIDE,
    MOVE,
    RESTORE,

    /** A tap on the category: play its first channel (the Live TV page's whole gesture). */
    PLAY,
    ;

    fun run(
        key: LiveKey,
        hide: (LiveKey) -> Unit,
        move: (LiveKey) -> Unit,
        restore: (LiveKey) -> Unit,
        play: (LiveKey) -> Unit,
    ) = when (this) {
        HIDE -> hide(key)
        MOVE -> move(key)
        RESTORE -> restore(key)
        PLAY -> play(key)
    }
}
