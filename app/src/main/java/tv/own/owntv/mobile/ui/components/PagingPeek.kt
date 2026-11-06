package tv.own.owntv.mobile.ui.components

import androidx.paging.compose.LazyPagingItems

/**
 * [LazyPagingItems.peek] for an index the list may no longer have.
 *
 * A layout pass can still be carrying the row indices of the list it held a frame ago — switching
 * Live TV (or the Guide) to a locked or simply smaller category does exactly that. `peek` throws for
 * such an index, and the whole app dies: `Illegal attempt to access index 0 in ItemSnapshotList of
 * size 0` is what opening a sealed category produced. A stale index means "no row there", not a fault.
 */
fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null
