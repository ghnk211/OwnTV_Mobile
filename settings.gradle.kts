pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // A libmpv built on this PC (OwnTV_libmpv's buildscripts/wsl_build.sh), only while
        // the build command carries -Powntv.libmpvLocalRepo=<folder> (never a file) — core's :player-core then asks
        // for version "local". Core's settings carry the same lines.
        providers.gradleProperty("owntv.libmpvLocalRepo").orNull?.let { dir ->
            maven {
                name = "LocalLibmpv"
                url = uri(file(dir))
                content { includeVersion("tv.own.owntv", "libmpv", "local") }
            }
        }
        google()
        mavenCentral()
        // OwnTV's public Maven repository. Only the mpv engine still comes from here:
        // tv.own.owntv:libmpv (https://github.com/ahXN00/OwnTV_libmpv). core and player-core are
        // built from vendor/OwnTV_Core below, and this filter makes a broken substitution fail
        // loudly instead of silently falling back to a published core.
        maven {
            name = "OwnTV"
            url = uri("https://ahxn00.github.io/OwnTV_Core/maven")
            content { includeModule("tv.own.owntv", "libmpv") }
        }
    }
}

// Core's source lives in this repository at vendor/OwnTV_Core — a git subtree of
// https://github.com/ghnk211/OwnTV_Core (branch `mobile`, forked from ahXN00/OwnTV_Core). Gradle
// substitutes the tv.own.owntv:core and :player-core coordinates with this build's modules by
// matching group:name, so a core edit reaches the app with no publish step and CI builds exactly
// the code committed here.
includeBuild("vendor/OwnTV_Core")

rootProject.name = "OwnTVMobile"
include(":app")
// Records the baseline profile against :app. Never built by CI — recording needs a real device.
include(":baselineprofile")
