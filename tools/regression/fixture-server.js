// TVbox 回归夹具服务：本地 CMS (type 0 JSON) + 配置 JSON + mp4 直链。
// 宿主机 0.0.0.0:8899；模拟器经 10.0.2.2:8899 访问。
// 用法：node fixture-server.js
const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const MP4 = path.join(__dirname, 'sample.mp4');

const CATS = [
  { type_id: 1, type_name: '电影' },
  { type_id: 2, type_name: '剧集' },
  { type_id: 3, type_name: '综艺' },
  { type_id: 6, type_name: '伦理片' }, // 儿童模式过滤用例
];

// 每分类 24 条，验证分页/网格；单集直链 → 本地 mp4（parse=0 直连路径）。
function movies(typeId) {
  const cat = CATS.find(c => c.type_id === typeId) || CATS[0];
  const list = [];
  for (let i = 1; i <= 24; i++) {
    list.push({
      vod_id: typeId * 100 + i,
      vod_name: `${cat.type_name}测试${String(i).padStart(2, '0')}`,
      vod_pic: 'http://10.0.2.2:8899/pic/' + typeId + '_' + i,
      vod_remarks: 'HD',
      vod_year: '2026',
      vod_area: '大陆',
      type_name: cat.type_name,
      vod_content: '本地回归夹具影片 ' + i,
      vod_play_from: '本地线路',
      vod_play_url: `第01集$http://10.0.2.2:8899/video.mp4#第02集$http://10.0.2.2:8899/video.mp4`,
    });
  }
  return list;
}

// 多集列表页（ac=list）只带 vod_id/vod_name/type_id —— 详情页走 ac=detail。
const LIST_CACHE = {};
for (const c of CATS) LIST_CACHE[c.type_id] = movies(c.type_id);

function json(res, o) {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(o));
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const ac = u.searchParams.get('ac') || '';
  const ids = u.searchParams.get('ids') || '';
  const t = u.searchParams.get('t') || '';
  const pg = parseInt(u.searchParams.get('pg') || '1', 10);
  const wd = u.searchParams.get('wd') || '';

  // 配置 JSON：一份本地 CMS 源
  if (u.pathname === '/tvbox-local.json') {
    return json(res, {
      spider: '',
      sites: [
        { key: 'local_cms', name: '本地CMS', type: 0, api: 'http://10.0.2.2:8899/api.php/provide/vod', searchable: 1, quickSearch: 1, filterable: 1 },
      ],
    });
  }

  if (u.pathname.startsWith('/pic/')) {
    // 1x1 png 占位海报
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
    res.writeHead(200, { 'Content-Type': 'image/png' });
    return res.end(png);
  }

  if (u.pathname.endsWith('/video.mp4')) {
    const stat = fs.statSync(MP4);
    const range = req.headers.range;
    if (range) {
      const m = /bytes=(\d+)-(\d*)/.exec(range);
      const start = parseInt(m[1], 10);
      const end = m[2] ? parseInt(m[2], 10) : stat.size - 1;
      res.writeHead(206, {
        'Content-Type': 'video/mp4',
        'Content-Range': `bytes ${start}-${end}/${stat.size}`,
        'Accept-Ranges': 'bytes',
      });
      return fs.createReadStream(MP4, { start, end }).pipe(res);
    }
    res.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': stat.size, 'Accept-Ranges': 'bytes' });
    return fs.createReadStream(MP4).pipe(res);
  }

  if (!u.pathname.endsWith('/vod')) {
    res.writeHead(404); return res.end('nope');
  }

  // CMS type 0 约定：ac=list 分类树；ac=detail 详情；ac=videolist 兜底/搜索
  if (ac === 'list') {
    return json(res, {
      code: 1,
      class: CATS,
      list: [], // ac=list 的 list 只有 id/name，首页列表由各分类 ac=detail 拉
    });
  }
  if (ac === 'detail' || ac === 'videolist') {
    if (ids) {
      const found = [];
      for (const c of CATS) {
        for (const m of LIST_CACHE[c.type_id]) {
          if (ids.split(',').includes(String(m.vod_id))) found.push(m);
        }
      }
      return json(res, { code: 1, list: found });
    }
    // 搜索：ac=detail&wd=关键词
    if (wd) {
      const hit = [];
      for (const c of CATS) {
        for (const m of LIST_CACHE[c.type_id]) {
          if (m.vod_name.includes(wd)) hit.push(m);
        }
      }
      return json(res, { code: 1, page: 1, pagecount: 1, total: hit.length, list: hit });
    }
    // 列表分页：t=分类 id，pg=页码（每页 12）；t 空 = 全部分类
    const all = t && LIST_CACHE[t] ? LIST_CACHE[t] : [].concat(...CATS.map(c => LIST_CACHE[c.type_id]));
    const pageSize = 12;
    const page = all.slice((pg - 1) * pageSize, pg * pageSize);
    return json(res, { code: 1, page: pg, pagecount: Math.ceil(all.length / pageSize), total: all.length, list: page });
  }
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ code: 0, msg: 'unknown ac' }));
});

server.listen(8899, '0.0.0.0', () => console.log('fixture server on :8899'));
