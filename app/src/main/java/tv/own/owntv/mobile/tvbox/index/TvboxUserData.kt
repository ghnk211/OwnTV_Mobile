package tv.own.owntv.mobile.tvbox.index

import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.backup.UserDataWriter

/**
 * The user-data half of a VOD row: favourite and resume position keyed by the Core `movieId`.
 *
 * Favourites go straight into the table (`OnConflictStrategy.IGNORE`, so a double tap is harmless);
 * removing one goes through [UserDataWriter], which records the tombstone a cross-device sync needs —
 * a plain delete would look like "absent here" and be merged back in from another device.
 */
class TvboxUserData(
    private val favoriteDao: FavoriteDao,
    private val progressDao: ProgressDao,
    private val movieDao: MovieDao,
    private val userDataWriter: UserDataWriter,
) {

    suspend fun isFavorite(profileId: Long, movieId: Long): Boolean =
        profileId >= 0 && favoriteDao.exists(profileId, MediaType.MOVIE, movieId)

    suspend fun toggleFavorite(profileId: Long, movieId: Long): Boolean {
        // A row keyed by profileId -1 has no profile to belong to; the foreign key would reject it.
        if (profileId < 0) return false
        val exists = favoriteDao.exists(profileId, MediaType.MOVIE, movieId)
        if (exists) {
            userDataWriter.removeFavorite(profileId, MediaType.MOVIE, movieId)
        } else {
            favoriteDao.add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId))
        }
        return !exists
    }

    /** The line/episode a film resumes on, as the JSON TVBOX wrote into `extKey`. */
    suspend fun progress(profileId: Long, movieId: Long): PlaybackProgressEntity? =
        progressDao.get(profileId, MediaType.MOVIE, movieId)

    suspend fun saveProgress(
        profileId: Long,
        movieId: Long,
        positionMs: Long,
        durationMs: Long,
        extKey: String?,
    ) {
        progressDao.save(
            PlaybackProgressEntity(
                profileId = profileId,
                mediaType = MediaType.MOVIE,
                itemId = movieId,
                positionMs = positionMs,
                durationMs = durationMs,
                extKey = extKey,
            ),
        )
    }

    suspend fun movie(movieId: Long) = movieDao.getById(movieId)
}