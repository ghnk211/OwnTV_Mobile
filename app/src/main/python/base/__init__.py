# -*- coding: utf-8 -*-
"""宿主提供的 Python Spider 契约包（docs/TVbox-PY源迁移方案.md §5.1）。

- spider.py：Spider 基类（五接口 + 桩方法空实现 + fetch/html/regStr helper）
- loader.py：用户脚本模块加载与 JSON 桥（Kotlin 侧 PySpider 调用入口）
"""