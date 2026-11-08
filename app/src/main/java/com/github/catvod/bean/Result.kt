package com.github.catvod.bean

import com.google.gson.JsonObject

/**
 * 桩：部分老 jar 依赖的返回模型（方案 5.3）。
 *
 * 关键设计：**字段一律用 `Any?` 而非具体类型**。
 * 生态里同一个逻辑字段在不同 jar 里类型不统一（`vod_id` 有 Int / String / Long 三种写法，
 * `vod_year` 有 String / Int），桩若写死类型，jar 访问时就会抛
 * `NoSuchFieldError: ... was of the wrong type`。
 * 用 `Any?` 由归一化层（JarResultParser）按运行时类型转换，才能兼容多种魔改。
 *
 * 依赖 com.google.gson（Gson 会随 spider jar 或显式依赖进入 classpath）。
 */
open class Result {
    @JvmField var code: Int = 0
    @JvmField var msg: String? = null
    @JvmField var data: Any? = null
    @JvmField var list: List<Any?>? = null
    @JvmField var classes: List<Any?>? = null
    @JvmField var page: Int = 1
    @JvmField var pagecount: Int = 1
    @JvmField var limit: Int = 0
    @JvmField var total: Int = 0
    @JvmField var filterDef: JsonObject? = null
}

/** 桩：影片模型（老协议）。字段类型同理全用 Any? 兼容各 jar 的类型差异。 */
open class Movie {
    @JvmField var vod_id: Any? = null
    @JvmField var vod_name: Any? = null
    @JvmField var vod_pic: Any? = null
    @JvmField var vod_remarks: Any? = null
    @JvmField var vod_year: Any? = null
    @JvmField var vod_area: Any? = null
    @JvmField var vod_actor: Any? = null
    @JvmField var vod_director: Any? = null
    @JvmField var vod_content: Any? = null
    @JvmField var vod_play_from: Any? = null
    @JvmField var vod_play_url: Any? = null
    @JvmField var vod_class: Any? = null
    @JvmField var vod_tag: Any? = null
    @JvmField var vod_letter: Any? = null
    @JvmField var vod_status: Any? = null
    @JvmField var vod_time: Any? = null
    @JvmField var vod_douban_score: Any? = null

    // ---- 生态里实际被 jar 赋值的扩展字段 ----
    // parent-first 下 jar 的 `new Movie().vod_pic_slide = x` 会走这个桩，
    // 字段缺失直接 NoSuchFieldError，故把常见的都补齐。
    @JvmField var vod_pic_slide: Any? = null
    @JvmField var vod_pic_thumb: Any? = null
    @JvmField var vod_actor_pic: Any? = null
    @JvmField var vod_descr: Any? = null
    @JvmField var vod_remarks_list: Any? = null
}

/** 桩：分类模型 */
open class Class {
    @JvmField var type_id: Any? = null
    @JvmField var type_name: Any? = null
    @JvmField var type_group: Any? = null
}

/** 桩：筛选条件模型 */
open class Filter {
    @JvmField var key: String? = null
    @JvmField var name: String? = null
    @JvmField var type: String? = null
    @JvmField var value: List<Any?>? = null
}

/** 桩：播放结果模型（部分 jar 直接返回它而非 Result） */
open class Play {
    @JvmField var parse: Int = 0
    @JvmField var playUrl: String? = null
    @JvmField var url: String? = null
    @JvmField var header: String? = null
    @JvmField var flag: String? = null
    @JvmField var urls: List<Any?>? = null
    @JvmField var subtitle: String? = null
    @JvmField var danmu: String? = null
}
