import io

P = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile\tvbox\repository\TvboxConfigRepository.kt'
s = io.open(P, encoding='utf-8').read()

# 1. regex: replace the mangled block with a properly escaped Kotlin regex literal
old = '''    /** 从缓存正文头部提取版本号（写在 encode 最前，正则一次命中） */
    private val SPACE = Regex.escape("\\s*")
    private val COLON = Regex.escape(":")
    private val DIGITS = Regex.escape("(\\d+)")
    val VERSION_REGEX = Regex(""_cacheVersion"\\\\s*:\\\\s*(\\\\d+)")'''
new = '''    /** 从缓存正文头部提取版本号（写在 encode 最前，正则一次命中） */
    val VERSION_REGEX = Regex("\\"_cacheVersion\\"\\\\s*:\\\\s*(\\\\d+)")'''
assert old in s, 'regex block not found'
s = s.replace(old, new)

# 2. encode body: CACHE_VERSION -> VERSION (object-local const)
old2 = '        append("{\\"_cacheVersion\\":").append(CACHE_VERSION).append(\',\')'
assert old2 in s, 'encode version ref'
s = s.replace(old2, '        append("{\\"_cacheVersion\\":").append(VERSION).append(\',\')')

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('codec fixed')

# 3. searchProgress property type: SharedFlow (subscriptionCount lives on SharedFlow)
P2 = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile\tvbox\repository\SourceRepository.kt'
s2 = io.open(P2, encoding='utf-8').read()
old3 = '    val searchProgress: Flow<SourceSearchResult> = _searchProgress.asSharedFlow()'
new3 = '    val searchProgress: kotlinx.coroutines.flow.SharedFlow<SourceSearchResult> = _searchProgress.asSharedFlow()'
assert old3 in s2, 'searchProgress prop'
s2 = s2.replace(old3, new3)
io.open(P2, 'w', encoding='utf-8', newline='\n').write(s2)
print('searchProgress typed')
