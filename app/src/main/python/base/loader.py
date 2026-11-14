# -*- coding: utf-8 -*-
"""用户 py 脚本的加载与 Kotlin ↔ Python 数据桥（docs/TVbox-PY源迁移方案.md §5.1）。

Kotlin 侧（PySpider/PyEngine）只经由本模块进入 Python：
- load_spider：按文件路径加载用户脚本并实例化模块级 Spider 子类
- loads / dumps：参数与返回值的 JSON 桥（dict/list 一律走 JSON，规避 Java 代理对象
  与 Python 原生容器的差异；str 原样传递）
- redirect_stdio：脚本 print 转发到宿主日志（进 logcat + app.log 环形日志）
"""
import importlib.util
import json
import sys

from base.spider import Spider as BaseSpider
from tv.own.owntv.mobile.tvbox.engine.py import PyHost

# 已加载的用户模块名（按 md5 命名，互不冲突；保留引用防 GC 卸载）
_LOADED = {}

# 生态脚本至少要实现其中一个（裸类形态的 sanity check，防随机类撞名 Spider）
INTERFACE_METHODS = (
    "init", "homeContent", "homeVideoContent", "categoryContent",
    "detailContent", "playerContent", "searchContent",
)


class _HostLogWriter:
    """文件式写入口：把脚本 print 按行转发给宿主日志。"""

    def __init__(self, level):
        self.level = level
        self._buf = ""

    def write(self, msg):
        self._buf += str(msg)
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            self._emit(line)

    def flush(self):
        if self._buf:
            self._emit(self._buf)
            self._buf = ""

    def _emit(self, line):
        text = line.strip()
        if text:
            PyHost.log(self.level, "python", text)


def redirect_stdio():
    """sys.stdout/stderr → 宿主日志。

    Chaquopy 默认只把 Python 输出打进 logcat（python.stdout 标签），不会进
    宿主的 app.log 环形日志 —— 真机排障时恰恰是拿 app.log 分析的，故转发一份。
    """
    for name, level in (("stdout", 2), ("stderr", 5)):
        try:
            stream = getattr(sys, name, None)
            if stream is not None and not isinstance(stream, _HostLogWriter):
                setattr(sys, name, _HostLogWriter(level))
        except Exception:
            pass


def loads(text):
    """JSON 文本 → Python 原生容器（Kotlin 侧构造 list/dict 参数用）。"""
    return json.loads(text)


def dumps(obj):
    """Python 对象 → JSON 文本（Kotlin 侧归一化前的统一出口）。

    ensure_ascii=False 保留中文原样，Kotlin 侧 Gson 解析不受影响，
    日志里也能直接看懂内容。
    """
    return json.dumps(obj, ensure_ascii=False)


def load_spider(path, module_name):
    """按文件路径加载用户脚本，返回模块级 Spider 子类实例。

    校验链（失败抛 ValueError，由 Kotlin 侧转成站点加载失败并计入源健康）：
    1. 模块里存在模块级 `Spider`，且是类；
    2. 不能是直接 import 的基类本身；
    3. 继承 base.spider.Spider **或**裸 `class Spider:` 鸭子类型（见下）。

    ## 两种生态形态都要认
    - 契约形态：`from base.spider import Spider; class Spider(Spider)` —— 本家写法；
    - **裸类形态**：`class Spider:` 不 import 任何宿主基类。fish2018/lib 系
      （clun.top/fun.json 的 28 个 py 源全是这个形态，自称"五壳通用独立 Spider"）
      靠鸭子类型跨 TVBox/F9/影视仓等壳运行。硬挑继承会把它们全毙掉。
    裸类动态混入 BaseSpider：MRO 上脚本自身方法优先，没实现的五接口落到
    基类空实现（返回 None → 上层按"无结果"处理，不是错误）。
    """
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ValueError("无法从该文件加载脚本：%s" % path)

    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(module_name, None)
        raise

    cls = getattr(module, "Spider", None)
    if cls is None or not isinstance(cls, type):
        raise ValueError("%s 未定义 Spider 类" % path)
    if cls is BaseSpider:
        raise ValueError("%s 未定义 Spider 子类（只 import 了基类）" % path)
    if not issubclass(cls, BaseSpider):
        # 裸类鸭子类型形态：先 sanity check（至少实现一个五接口，防随机类撞名），
        # 再动态混入基类拿默认实现。check 必须在混入前做——混入后基类方法
        # 会让 any() 恒真。
        if not any(callable(getattr(cls, m, None)) for m in INTERFACE_METHODS):
            raise ValueError("%s 的 Spider 未实现任何五接口方法" % path)
        cls = type("Spider", (cls, BaseSpider), {})

    instance = cls()
    _LOADED[module_name] = module
    return instance