import io

B = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile'

# 3. Pages: long-press menu on category chips + lock event banner
p = B + r'\ui\screens\vod\TvboxPages.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''internal fun TvboxHomePage(
    configState: TvboxConfigUiState,
    state: TvboxHomeUiState,
    onOpenConfig: () -> Unit,
    onOpenSite: (SiteConfig) -> Unit,
    onSelectCategory: (String, String) -> Unit,''',
'''internal fun TvboxHomePage(
    configState: TvboxConfigUiState,
    state: TvboxHomeUiState,
    onOpenConfig: () -> Unit,
    onOpenSite: (SiteConfig) -> Unit,
    onSelectCategory: (String, String) -> Unit,
    onToggleLock: (String, String) -> Unit,
    onConsumeLockEvent: () -> Unit,''')

s = s.replace('''                    itemsIndexed(state.categories, key = { _, c -> c.typeId + c.typeName }) { _, cat ->
                        val sealed = cat.typeId in state.sealedKeys
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.cardSolid)
                                .clickable { onSelectCategory(cat.typeId, cat.typeName) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {''',
'''                    itemsIndexed(state.categories, key = { _, c -> c.typeId + c.typeName }) { _, cat ->
                        val sealed = cat.typeId in state.sealedKeys
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.cardSolid)
                                .combinedClickable(
                                    onClick = { onSelectCategory(cat.typeId, cat.typeName) },
                                    // plan §5.5.7: the category row's "..." entry — long press locks.
                                    onLongClick = { lockMenuFor = cat },
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {''')

s = s.replace('''    var siteMenu by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    val lockedPrefix = stringResource(R.string.tvbox_locked_prefix)
''', '''    var siteMenu by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var lockMenuFor by remember { mutableStateOf<tv.own.owntv.mobile.tvbox.engine.ClassInfo?>(null) }
    val lockedPrefix = stringResource(R.string.tvbox_locked_prefix)

    // Lock/unlock feedback (no-PIN guidance included).
    state.lockEvent?.let { event ->
        val message = stringResource(
            when (event) {
                TvboxLockEvent.LOCKED -> R.string.tvbox_lock_locked
                TvboxLockEvent.UNLOCKED -> R.string.tvbox_lock_unlocked
                TvboxLockEvent.NO_PIN -> R.string.tvbox_lock_no_pin
            },
        )
        LaunchedEffect(event) { onConsumeLockEvent() }
        Text(
            message,
            style = stageText(12f, 600),
            color = palette.text,
            modifier = Modifier
                .padding(horizontal = PageSidePadding, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.cardSolid)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
''')

s = s.replace('''    // Site picker sheet
    if (siteMenu) {
        SitePickerSheet(''', '''    // plan §5.5.7: long-press menu → lock/unlock (StageMenuItem).
    lockMenuFor?.let { cat ->
        val sealed = cat.typeId in state.sealedKeys
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageSidePadding, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSolid)
                .padding(vertical = 6.dp),
        ) {
            Text(
                cat.typeName,
                style = stageText(13f, 700),
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StageMenuItem(
                text = stringResource(if (sealed) R.string.tvbox_lock_action_unlock else R.string.tvbox_lock_action_lock),
                icon = MobileIcons.Check,
                onClick = {
                    onToggleLock(cat.typeId, cat.typeName)
                    lockMenuFor = null
                },
            )
            StageMenuItem(
                text = stringResource(R.string.common_cancel),
                icon = MobileIcons.Close,
                onClick = { lockMenuFor = null },
            )
        }
    }

    // Site picker sheet
    if (siteMenu) {
        SitePickerSheet(''')

s = s.replace('import androidx.compose.foundation.clickable\n',
              'import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.combinedClickable\n')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('pages patched')

# 4. VodScreen: pass the new callbacks
p = B + r'\ui\screens\vod\VodScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''            onOpenSite = homeVm::openSite,
            onSelectCategory = homeVm::selectCategory,''',
'''            onOpenSite = homeVm::openSite,
            onSelectCategory = homeVm::selectCategory,
            onToggleLock = homeVm::toggleLock,
            onConsumeLockEvent = homeVm::consumeLockEvent,''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('screen patched')

# 5. strings
blocks = {
 'app/src/main/res/values/strings_mobile.xml': '''    <string name="tvbox_lock_action_lock">Lock this category</string>
    <string name="tvbox_lock_action_unlock">Unlock this category</string>
    <string name="tvbox_lock_locked">Category locked.</string>
    <string name="tvbox_lock_unlocked">Category unlocked for this session.</string>
    <string name="tvbox_lock_no_pin">Set a Customize PIN in Settings first, then come back to lock here.</string>
''',
 'app/src/main/res/values-zh-rCN/strings_mobile.xml': '''    <string name="tvbox_lock_action_lock">上锁该分类</string>
    <string name="tvbox_lock_action_unlock">解锁该分类</string>
    <string name="tvbox_lock_locked">分类已上锁。</string>
    <string name="tvbox_lock_unlocked">分类已解锁（本次会话内有效）。</string>
    <string name="tvbox_lock_no_pin">请先在 设置 → Customize 设置 PIN，再回到这里上锁。</string>
''',
 'app/src/main/res/values-zh-rTW/strings_mobile.xml': '''    <string name="tvbox_lock_action_lock">上鎖該分類</string>
    <string name="tvbox_lock_action_unlock">解鎖該分類</string>
    <string name="tvbox_lock_locked">分類已上鎖。</string>
    <string name="tvbox_lock_unlocked">分類已解鎖（本次會話內有效）。</string>
    <string name="tvbox_lock_no_pin">請先在 設定 → Customize 設定 PIN，再回到這裡上鎖。</string>
''',
}
ROOT = 'D:\\AndroidStudioProjects\\git\\Qwn\\OwnTV_Mobile\\'
for path, block in blocks.items():
    fp = ROOT + path.replace('/', '\\')
    c = io.open(fp, encoding='utf-8').read()
    c = c.replace('</resources>', block + '</resources>')
    io.open(fp, 'w', encoding='utf-8', newline='\n').write(c)
print('strings patched')
