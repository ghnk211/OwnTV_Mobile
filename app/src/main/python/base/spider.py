# -*- coding: utf-8 -*-
"""Python 采集源的宿主契约基类（docs/TVbox-PY源迁移方案.md §5.1）。

生态脚本的固定写法：
    from base.spider import Spider
    class Spider(Spider): ...        # 模块级 Spider 名被 rebind 成子类

宿主保证：
- 五接口 + 协议桩方法为空实现，脚本按需覆盖；
- helper（fetch/html/regStr/config/header）全部做缺省保护 —— 生态脚本写法极不统一，
  常见 `def __init__(self)` 不调 super()、无 ext 时 `self.config['filter']` KeyError，
  故 config/header 用 property 惰性兜底，任何脚本形态都能拿到可用默认值。
"""
import base64
import json
import re

from tv.own.owntv.mobile.tvbox.engine.py import PyHost


class Response:
    """requests 风格的最小响应对象（契约：.text / .content / .json() / .status_code）。"""

    def __init__(self, code, url, content, charset=None):
        self.status_code = code          # HTTP 状态码（失败为 -1）
        self.url = url                   # 最终地址（含跳转）
        self._content = content or b""   # 原始字节
        self._charset = charset          # 服务端声明的字符集，可为空
        self._text = None                # 惰性解码缓存

    @property
    def content(self):
        """原始字节（解码/解密类脚本使用）。"""
        return self._content

    @property
    def text(self):
        """按 声明字符集 → utf-8 → gb18030 → latin-1 逐级兜底解码。"""
        if self._text is None:
            self._text = self._decode()
        return self._text

    def _decode(self):
        for enc in (self._charset, "utf-8", "gb18030", "latin-1"):
            if not enc:
                continue
            try:
                return self._content.decode(enc)
            except (LookupError, UnicodeDecodeError):
                continue
        return self._content.decode("utf-8", "replace")

    def json(self):
        """按 JSON 解析响应体。"""
        return json.loads(self.text)


class Spider:
    """五接口 + 桩方法的空实现基类；脚本按需覆盖。"""

    def __init__(self):
        self._config = {"filter": {}}
        self._header = {}

    # ---------------- 协议属性（property 惰性兜底） ----------------

    @property
    def config(self):
        """站点 ext 解析出的 dict；缺省 {'filter': {}}（防无 ext 时 KeyError）。"""
        cfg = getattr(self, "_config", None)
        return cfg if cfg is not None else {"filter": {}}

    @config.setter
    def config(self, value):
        self._config = value if isinstance(value, dict) else {"filter": {}}

    @property
    def header(self):
        """默认请求头（UA 等，宿主全局 UA 由网络层兜底）。"""
        h = getattr(self, "_header", None)
        return h if h is not None else {}

    @header.setter
    def header(self, value):
        self._header = value if isinstance(value, dict) else {}

    # ---------------- 五接口（返回 TVBox 协议 dict） ----------------

    def init(self, extend=""):
        """生命周期：解析站点 ext 进 self.config。"""
        cfg = {"filter": {}}
        text = extend if isinstance(extend, str) else ""
        if text.strip():
            try:
                data = json.loads(text)
                if isinstance(data, dict):
                    cfg.update(data)
            except ValueError:
                # 非 JSON 的裸 ext（生态常见）：保留原文，不丢信息
                cfg["value"] = text
        self.config = cfg

    def getName(self):
        """脚本自述名（调试用）。"""
        return self.__class__.__name__

    def destroy(self):
        """释放实例资源（脚本按需覆盖）。"""
        pass

    def homeContent(self, filter):
        """首页分类 + 筛选树：{'class': [...], 'filters': {...}}。"""
        return None

    def homeVideoContent(self):
        """首页推荐（homeContent 无推荐时的兜底接口）。"""
        return None

    def categoryContent(self, tid, pg, filter, extend):
        """分类列表：extend 为筛选选中项 dict。"""
        return None

    def detailContent(self, ids):
        """详情 + 播放线路/选集：ids 为 id 列表。"""
        return None

    def playerContent(self, flag, id, vipFlags):
        """取播放地址：{'parse': 0/1, 'url': ..., 'header': {...}}。"""
        return None

    def searchContent(self, key, quick, pg):
        """搜索。"""
        return None

    # ---------------- 协议桩方法 ----------------

    def isVideoFormat(self, url):
        """是否为可播放的直链形态（默认否，交由宿主判定）。"""
        return False

    def manualVideoCheck(self):
        """是否需要人工确认播放（默认否）。"""
        return False

    def localProxy(self, param):
        """本地代理回调（默认不支持）。"""
        return None

    def getDependence(self):
        """声明 pip 依赖；宿主只能对未预装依赖告警（pip 是 build 期的）。"""
        return []

    # ---------------- helper ----------------

    def fetch(self, url, headers=None):
        """同步 HTTP：桥到宿主 OkHttp（UA 池/DoH/代理/站点冷却全复用）。"""
        merged = {}
        merged.update(self.header or {})
        if headers:
            merged.update(headers)
        site_key = getattr(self, "site_key", "") or ""
        raw = PyHost.fetch(
            url,
            json.dumps(merged, ensure_ascii=False),
            site_key,
            20000,
        )
        data = json.loads(raw)
        content = base64.b64decode(data.get("content", "") or "")
        return Response(data.get("code", -1), data.get("url", url), content, data.get("charset"))

    def html(self, text):
        """文本 → lxml etree（脚本用 .xpath(...)）；惰性导入省冷启开销。"""
        from lxml import etree
        return etree.HTML(text)

    def regStr(self, text, pattern):
        """正则 search：有分组取 group(1)，无分组取 group(0)；未命中返回空串。"""
        if text is None or pattern is None:
            return ""
        m = re.search(pattern, str(text), re.S)
        if not m:
            return ""
        return m.group(1) if m.groups() else m.group(0)