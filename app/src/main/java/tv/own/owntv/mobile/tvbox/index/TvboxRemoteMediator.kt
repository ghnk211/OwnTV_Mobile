package tv.own.owntv.mobile.tvbox.index

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import tv.own.owntv.core.database.dao.CatalogIndexCursorDao
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import java.io.IOException

/**
 * The remote boundary of one TVBOX category in the Library pager (plan §4.8 B2). Room stays the
 * single source of truth: this only writes pages in, it never serves rows itself — search, sort,
 * hide and favourite keep working on the indexed rows with no TVBOX-specific code.
 *
 * [fetchPage] and [indexPage] are the two side effects, injected so the page state machine below
 * runs in a JVM test; the factory wires them to the repository and the indexer.
 */
@OptIn(ExperimentalPagingApi::class)
class TvboxRemoteMediator(
    site: SiteConfig,
    typeId: String,
    private val sourceId: Long,
    private val cursorDao: CatalogIndexCursorDao,
    private val fetchPage: suspend (page: Int) -> SpiderResult?,
    private val indexPage: suspend (page: Int, movies: List<Movie>) -> Unit,
) : RemoteMediator<Int, MovieEntity>() {

    // Same identity as CategoryEntity.remoteId and the cursor's primary key — a bare typeId would
    // let two sites' same-named categories share one page position.
    private val categoryKey = TvboxIndexMapper.categoryRemoteId(site.key, typeId)

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MovieEntity>): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> {
                cursorDao.reset(sourceId, categoryKey)
                1
            }
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> (cursorDao.lastPage(sourceId, categoryKey) ?: 0) + 1
        }
        // A failed fetch is an error, never "no next page" — ending here would silently truncate
        // the category at whatever the index already holds.
        val result = fetchPage(page)
            ?: return MediatorResult.Error(IOException("categoryContent failed: $categoryKey page $page"))
        indexPage(page, result.list)
        return MediatorResult.Success(
            endOfPaginationReached = result.list.isEmpty() || page >= result.pagecount,
        )
    }
}

/**
 * Builds the mediator for a Library folder when — and only when — that folder is a category of the
 * active TVBOX configuration. Every other selection (IPTV folder, All, Favorites, custom) pages
 * Room alone.
 */
class TvboxRemoteMediatorFactory(
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val configRepository: TvboxConfigRepository,
    private val sourceRepository: SourceRepository,
    private val indexer: TvboxIndexer,
    private val cursorDao: CatalogIndexCursorDao,
) {
    suspend fun forFolder(categoryId: Long): TvboxRemoteMediator? {
        val category = categoryDao.getById(categoryId) ?: return null
        val remoteId = category.remoteId ?: return null
        val source = sourceDao.getById(category.sourceId) ?: return null
        if (source.type != SourceType.TVBOX) return null
        // Only the active configuration's sites are resolvable; a folder left over from a
        // switched-away configuration browses its index without a remote boundary.
        if (source.externalKey != configRepository.activeId().toString()) return null
        val siteKey = remoteId.substringBefore('/', "")
        val typeId = remoteId.substringAfter('/', "")
        if (siteKey.isEmpty() || typeId.isEmpty()) return null
        val site = configRepository.siteMap[siteKey] ?: return null
        if (!site.isSupported) return null
        val sourceId = category.sourceId
        val typeName = category.name
        return TvboxRemoteMediator(
            site = site,
            typeId = typeId,
            sourceId = sourceId,
            cursorDao = cursorDao,
            fetchPage = { page -> sourceRepository.categoryContent(site, typeId, page.toString()) },
            indexPage = { page, movies -> indexer.indexCategoryPage(sourceId, site, typeId, typeName, movies, page) },
        )
    }
}