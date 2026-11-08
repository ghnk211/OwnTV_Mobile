package com.github.catvod.crawler

import android.content.Context

/**
 * 桩：占位基类（部分 jar 继承它而非 Spider）。
 * 继承 Spider 保证已有 override 仍然生效。
 */
abstract class SpiderZero : Spider() {
    override fun init(context: Context, ext: String?) {
        super.init(context, ext)
    }
}
