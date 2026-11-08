import io
B = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile'

# 1. HomeVM: track the current category's typeId and expose it
p = B + r'\ui\screens\vod\TvboxViewModels.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    /** 当前分类（[tid, name]），详情/播放要带去当锁与儿童判定的身份。 */
    private var currentCategoryName: String = ""''',
'''    /** 当前分类（tid/name），详情/播放要带去当锁与儿童判定的身份（方案 §5.5.3 key 口径）。 */
    private var currentCategoryName: String = ""
    private var currentCategoryTid: String = ""

    /** 供 UI 把海报的来源分类身份带给详情（typeId 优先的锁 key 必须在列表层确定）。 */
    val currentCategoryContext: Pair<String, String>
        get() = currentCategoryTid to currentCategoryName''')
s = s.replace('''    private fun loadCategory(tid: String, typeName: String, replace: Boolean, page: Int = 1, extend: String? = null) {
        val site = _state.value.currentSite ?: return
        if (replace) {
            currentCategoryName = typeName''',
'''    private fun loadCategory(tid: String, typeName: String, replace: Boolean, page: Int = 1, extend: String? = null) {
        val site = _state.value.currentSite ?: return
        if (replace) {
            currentCategoryName = typeName
            currentCategoryTid = tid''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('home vm patched')

# 2. Pages: poster click carries the category context
p = B + r'\ui\screens\vod\TvboxPages.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    val palette = LocalStagePalette.current
    var siteMenu by remember { mutableStateOf(false) }''',
'''    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenDetail: (String, String, String, String) -> Unit,
    categoryContext: Pair<String, String>,
) {
    val palette = LocalStagePalette.current
    var siteMenu by remember { mutableStateOf(false) }''')
s = s.replace('''                PosterGrid(
                    movies = state.movies,
                    currentSiteKey = state.currentSite?.key,
                    onOpenDetail = onOpenDetail,''',
'''                PosterGrid(
                    movies = state.movies,
                    onOpenDetail = { site, vod -> onOpenDetail(site, vod, categoryContext.first, categoryContext.second) },''')
s = s.replace('''private fun PosterGrid(
    movies: List<Movie>,
    currentSiteKey: String?,
    onOpenDetail: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    modifiers: Map<String, String?>,
) {''',
'''private fun PosterGrid(
    movies: List<Movie>,
    onOpenDetail: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    modifiers: Map<String, String?>,
) {''')
s = s.replace('''                // A home-row movie may have been borrowed from another site (donorSiteKey);
                // rows from the current site's own list belong to that site.
                onClick = { onOpenDetail(movie.donorSiteKey ?: currentSiteKey ?: "", movie.id) },''',
'''                // A home-row movie may have been borrowed from another site (donorSiteKey);
                // rows from the current site's own list belong to that site.
                onClick = { onOpenDetail(movie.donorSiteKey ?: "", movie.id) },''')
# search page openDetail gains no category context (search has none) — keep its own signature
s = s.replace('''    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onClose: () -> Unit,
) {''',
'''    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onClose: () -> Unit,
) {''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('pages patched')

# 3. VodScreen: pass context + carry to detail
p = B + r'\ui\screens\vod\VodScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    var page by remember { mutableStateOf(VodPage.HOME) }
    var pendingDetail by remember { mutableStateOf<Pair<String, String>?>(null) }''',
'''    var page by remember { mutableStateOf(VodPage.HOME) }
    // (siteKey, vodId, typeId, typeName): the list layer owns the lock-key identity (plan §5.5.3).
    var pendingDetail by remember { mutableStateOf<List<String>?>(null) }''')
s = s.replace('''            onOpenDetail = { siteKey, vodId ->
                pendingDetail = siteKey to vodId
                detailVm.open(siteKey, vodId)
                page = VodPage.DETAIL
            },
        )''',
'''            onOpenDetail = { siteKey, vodId, typeId, typeName ->
                pendingDetail = listOf(siteKey, vodId, typeId, typeName)
                detailVm.open(siteKey, vodId, typeId, typeName)
                page = VodPage.DETAIL
            },
            categoryContext = homeVm.currentCategoryContext,
        )''')
s = s.replace('''        VodPage.DETAIL -> {
            val target = pendingDetail
            if (target == null) {
                page = VodPage.HOME
            } else {
                TvboxDetailPage(''', '''        VodPage.DETAIL -> {
            val target = pendingDetail
            if (target == null) {
                page = VodPage.HOME
            } else {
                TvboxDetailPage(''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('screen patched')

# 4. DetailVM.open gains typeId/typeName params; base key carries both
s = io.open(p := B + r'\ui\screens\vod\TvboxViewModels.kt', encoding='utf-8').read()
s = s.replace('''    /** 打开详情：儿童模式命中直接拒绝；锁命中标记 sealed（UI 弹 PIN）。 */
    fun open(siteKey: String, vodId: String) {
        val site = configRepository.siteMap[siteKey] ?: return
        viewModelScope.launch {
            _state.value = TvboxDetailUiState(site = site, vodId = vodId, loading = true)
            val pid = settings.activeProfileId.first()
            val profile = profileDao.getById(pid)
            val baseKey = TvboxCategoryKey(site.key, site.name, null, "")''',
'''    /** 打开详情：儿童模式命中直接拒绝；锁命中标记 sealed（UI 弹 PIN）。
     *  [typeId]/[typeName] 来自列表层 —— 锁 key 的 typeId 在列表页就确定了（§5.5.3）。 */
    fun open(siteKey: String, vodId: String, typeId: String?, typeName: String?) {
        val site = configRepository.siteMap[siteKey] ?: return
        viewModelScope.launch {
            _state.value = TvboxDetailUiState(site = site, vodId = vodId, loading = true)
            val pid = settings.activeProfileId.first()
            val profile = profileDao.getById(pid)
            val baseKey = TvboxCategoryKey(site.key, site.name, typeId, typeName.orEmpty())''')
s = s.replace('''            if (lock.seals(pid, site.key, null, "")) {
                _state.value = _state.value.copy(loading = false, sealed = true)
                return@launch
            }''',
'''            if (lock.seals(pid, site.key, baseKey.typeId, baseKey.typeName)) {
                _state.value = _state.value.copy(loading = false, sealed = true)
                return@launch
            }''')
s = s.replace('''            // 详情层二次校验：typeName 现在有了（方案 §5.5.6 第二层）
            val key = (categoryKey ?: TvboxCategoryKey(site.key, site.name, null, "")).copy(typeName = movie.typeName ?: "")''',
'''            // 详情层二次校验：key 沿用列表层身份（typeId 不因详情返回而漂移，方案 R14 口径）
            val key = (categoryKey ?: baseKey)''')
s = s.replace('''            // 详情层二次校验：typeName 现在有了（方案 §5.5.6 第二层）
            val key = (categoryKey ?: baseKey).copy(typeName = movie.typeName ?: "")
            categoryKey = key''',
'''            // 详情层二次校验：key 沿用列表层身份（typeId 不因详情返回而漂移，方案 R14 口径）
            val key = categoryKey ?: baseKey
            categoryKey = key''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('detail vm patched')
