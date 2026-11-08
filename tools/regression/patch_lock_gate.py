import io
B = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile'

# 1. HomeVM: unlockCategory with PIN
p = B + r'\ui\screens\vod\TvboxViewModels.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    fun consumeLockEvent() {
        _state.value = _state.value.copy(lockEvent = null)
    }''', '''    fun consumeLockEvent() {
        _state.value = _state.value.copy(lockEvent = null)
    }

    /**
     * 点击已锁定分类：PIN 通过后放行进入（方案 §5.5.6 列表层强校验）。
     * epoch 防绕过协议在 [TvboxCategoryLock.unlock] 内；失败由 UI 出 WRONG/WAIT。
     */
    suspend fun unlockCategory(tid: String, typeName: String, pin: String): PinAttempt {
        val site = _state.value.currentSite ?: return PinAttempt.Wrong
        val pid = settings.activeProfileId.first()
        val result = lock.unlock(pid, site.key, tid, typeName, pin)
        if (result is PinAttempt.Accepted) {
            selectCategory(tid, typeName)
        }
        return result
    }''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('vm patched')

# 2. Pages: sealed chip click opens PIN sheet
p = B + r'\ui\screens\vod\TvboxPages.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''internal fun TvboxHomePage(
    configState: TvboxConfigUiState,
    state: TvboxHomeUiState,
    onOpenConfig: () -> Unit,
    onOpenSite: (SiteConfig) -> Unit,
    onSelectCategory: (String, String) -> Unit,
    onToggleLock: (String, String) -> Unit,
    onConsumeLockEvent: () -> Unit,''',
'''internal fun TvboxHomePage(
    configState: TvboxConfigUiState,
    state: TvboxHomeUiState,
    onOpenConfig: () -> Unit,
    onOpenSite: (SiteConfig) -> Unit,
    onSelectCategory: (String, String) -> Unit,
    onToggleLock: (String, String) -> Unit,
    onConsumeLockEvent: () -> Unit,
    onUnlockCategory: suspend (String, String, String) -> PinAttempt,''')

s = s.replace('''                                .combinedClickable(
                                    onClick = { onSelectCategory(cat.typeId, cat.typeName) },
                                    // plan §5.5.7: the category row's "..." entry — long press locks.
                                    onLongClick = { lockMenuFor = cat },
                                )''',
'''                                .combinedClickable(
                                    // plan §5.5.6: a locked row shows its lock and pops the PIN sheet.
                                    onClick = {
                                        if (sealed) pinSheetFor = cat else onSelectCategory(cat.typeId, cat.typeName)
                                    },
                                    // plan §5.5.7: the category row's "..." entry — long press locks.
                                    onLongClick = { lockMenuFor = cat },
                                )''')

s = s.replace('''    var lockMenuFor by remember { mutableStateOf<tv.own.owntv.mobile.tvbox.engine.ClassInfo?>(null) }
    val lockedPrefix = stringResource(R.string.tvbox_locked_prefix)''',
'''    var lockMenuFor by remember { mutableStateOf<tv.own.owntv.mobile.tvbox.engine.ClassInfo?>(null) }
    var pinSheetFor by remember { mutableStateOf<tv.own.owntv.mobile.tvbox.engine.ClassInfo?>(null) }
    val lockedPrefix = stringResource(R.string.tvbox_locked_prefix)''')

s = s.replace('''    // Site picker sheet
    if (siteMenu) {
        SitePickerSheet(''', '''    // Locked-category PIN sheet (list-layer gate, plan §5.5.6).
    pinSheetFor?.let { cat ->
        var waitSeconds by remember { mutableStateOf(0) }
        var wrong by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PinEntrySheet(
            title = pinEntryTitle(wrong = wrong, waitSeconds = waitSeconds),
            description = stringResource(R.string.tvbox_pin_unlock_desc),
            onSubmit = { entered ->
                scope.launch {
                    when (onUnlockCategory(cat.typeId, cat.typeName, entered)) {
                        PinAttempt.Accepted -> pinSheetFor = null
                        PinAttempt.Wrong -> wrong = true
                        is PinAttempt.Wait -> {
                            wrong = false
                            waitSeconds = 30
                        }
                    }
                }
            },
            onDismiss = { pinSheetFor = null },
        )
    }

    // Site picker sheet
    if (siteMenu) {
        SitePickerSheet(''')

s = s.replace('import tv.own.owntv.core.categorylock.PinAttempt\n',
              'import tv.own.owntv.core.categorylock.PinAttempt\n')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('pages patched')

# 3. VodScreen wiring
p = B + r'\ui\screens\vod\VodScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''            onToggleLock = homeVm::toggleLock,
            onConsumeLockEvent = homeVm::consumeLockEvent,''',
'''            onToggleLock = homeVm::toggleLock,
            onConsumeLockEvent = homeVm::consumeLockEvent,
            onUnlockCategory = homeVm::unlockCategory,''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('screen patched')
