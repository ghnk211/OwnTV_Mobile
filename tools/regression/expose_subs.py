import io

P = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile\tvbox\repository\SourceRepository.kt'
s = io.open(P, encoding='utf-8').read()

old = '''    /** 聚合搜索的流式结果（先到先显示） */
    val searchProgress: kotlinx.coroutines.flow.SharedFlow<SourceSearchResult> = _searchProgress.asSharedFlow()'''
new = '''    /** 聚合搜索的流式结果（先到先显示） */
    val searchProgress: kotlinx.coroutines.flow.SharedFlow<SourceSearchResult> = _searchProgress.asSharedFlow()

    /** 订阅数：调用方先等它 >0 再触发搜索，快源 emission 才不会丢在订阅前 */
    val searchProgressSubscribers = _searchProgress.subscriptionCount'''
assert old in s, 'searchProgress block'
s = s.replace(old, new)
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('exposed')

P2 = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile\ui\screens\vod\TvboxViewModels.kt'
s2 = io.open(P2, encoding='utf-8').read()
old2 = '            sourceRepository.searchProgress.subscriptionCount.first { it > 0 }'
new2 = '            sourceRepository.searchProgressSubscribers.first { it > 0 }'
assert old2 in s2, 'vm barrier'
s2 = s2.replace(old2, new2)
io.open(P2, 'w', encoding='utf-8', newline='\n').write(s2)
print('vm barrier switched')
