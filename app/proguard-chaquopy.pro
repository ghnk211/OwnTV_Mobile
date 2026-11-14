# ==================== Python 采集源桥（Chaquopy，仅完整包挂载） ====================
# Python 侧按类名调用宿主桥（PyHost.fetch/log，
# `from tv.own.owntv.mobile.tvbox.engine.py import PyHost`），R8 改名即
# NoSuchMethod/断链，必须 keep。
-keep class tv.own.owntv.mobile.tvbox.engine.py.** { *; }
-dontwarn com.chaquo.python.**