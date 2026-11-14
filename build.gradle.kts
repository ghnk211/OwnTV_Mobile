// Root build file for the OwnTV mobile app. Everything the app is built on — the database, sync,
// EPG, backup, settings storage, the playback engine and every user-visible string — comes from the
// core library repository (ahXN00/OwnTV_Core); this repo holds the phone/tablet shell only.
plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin comes from AGP 9's built-in Kotlin support. The Compose compiler plugin is pinned to
    // that Kotlin version, exactly as in the TV app and in core.
    alias(libs.plugins.compose.compiler) apply false
    // Baseline profiles — applied by :app and by :baselineprofile. `com.android.test` is declared
    // here rather than in the module because it ships inside AGP and is already on the build
    // classpath by the time a module asks for it by version; Gradle then refuses to version-check it
    // and fails with "already on the classpath with an unknown version".
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    // Python collection sources (TVbox .py spiders). Declared here so the app module can apply
    // it conditionally behind the WITH_PY switch, and so the plugin is on the build classpath
    // when the app asks for it by id.
    alias(libs.plugins.chaquopy.python) apply false
}
