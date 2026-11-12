import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    // Kotlin is provided by AGP 9's built-in Kotlin support; this plugin pins the Compose compiler
    // to the same version the TV app and core use.
    alias(libs.plugins.compose.compiler)
    // Consumes :baselineprofile's recording and packages it as baseline.prof.
    alias(libs.plugins.baselineprofile)
}

android {
    // Distinct from the TV app's tv.own.owntv, so both install side by side on one device and each
    // keeps its own data. This value can never change after the first release — the launcher label
    // and icon can.
    namespace = "tv.own.owntv.mobile"
    compileSdk {
        version = release(37)
    }

    // Signing credentials AND local-only build switches, kept in a standalone properties file OUTSIDE
    // the repo. Gradle only reads gradle.properties from GRADLE_USER_HOME or the project dir, so this
    // one is loaded by hand. Declared here because defaultConfig below already needs it.
    val localSigningProps = Properties().apply {
        // 本机签名与开关统一放在仓库外的 keyStore 目录（以当前本机路径为准）。
        val f = File("E:/develop/ideaPoject/git/Own/keyStore/owntv-signing.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }

    defaultConfig {
        applicationId = "tv.own.owntv.mobile"
        minSdk = 26
        targetSdk = 36
        // CI injects these from the git tag, exactly as in the TV app. The fallbacks are only used
        // by local/debug builds and are pinned HIGH so a dev APK is always "newer" than a published
        // release and installs straight over it.
        versionCode = (System.getenv("VERSION_CODE") ?: "99999").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "99.99.99"

        // The three switches core reads through CoreBuildInfo. Same resolution order as the TV app:
        // env var (CI) > Gradle property > the out-of-repo properties file.
        buildConfigField(
            "boolean",
            "DIAGNOSTIC_BUILD",
            (providers.gradleProperty("diagnosticBuild").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean",
            "DEV_TOOLS",
            (
                (
                    providers.gradleProperty("owntv.devTools").orNull
                        ?: localSigningProps.getProperty("owntv.devTools")
                    ) == "true"
                ).toString(),
        )
        // Shared secret the metadata Worker's edge rule requires (`x-owntv-key`). NEVER in the repo.
        // A blank key is a working configuration: core falls back to the unprotected base URL.
        val edgeKey = System.getenv("OWNTV_EDGE_KEY")
            ?: providers.gradleProperty("owntv.edgeKey").orNull
            ?: localSigningProps.getProperty("owntv.edgeKey")
            ?: ""
        buildConfigField("String", "TMDB_EDGE_KEY", "\"${edgeKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ABI split via product flavors, mirroring the TV app: `standard` is what real phones and tablets
    // run, `x86_64` exists for the emulator. The player engine ships large prebuilt .so files, so a
    // universal APK would be roughly double the size for no one's benefit.
    flavorDimensions += "abi"
    productFlavors {
        create("standard") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    // The SAME keystore and the same property names as the TV app. A signing certificate must be
    // stable per applicationId, not unique per app, and these two apps are maintained by one person
    // — a second keystore would only add a second thing to lose. Env vars first (that is how CI
    // injects the GitHub secrets), then user-wide Gradle properties, then the out-of-repo file.
    // Nothing configured (a fork, a fresh clone) still builds; the APK is just unsigned.
    fun signingValue(env: String, property: String): String? =
        System.getenv(env)
            ?: providers.gradleProperty(property).orNull
            ?: localSigningProps.getProperty(property)

    val releaseKeystore = signingValue("KEYSTORE_FILE", "owntv.keystoreFile")
    val testKeystore = signingValue("TEST_KEYSTORE_FILE", "owntv.testKeystoreFile")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("KEYSTORE_PASSWORD", "owntv.keystorePassword")
                keyAlias = signingValue("KEY_ALIAS", "owntv.keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "owntv.keyPassword")
            }
        }
        // 测试包使用独立 keystore；缺失时回退到 Android 默认 debug 签名。
        if (testKeystore != null) {
            create("test") {
                storeFile = file(testKeystore)
                storePassword = signingValue("TEST_KEYSTORE_PASSWORD", "owntv.testKeystorePassword")
                keyAlias = signingValue("TEST_KEY_ALIAS", "owntv.testKeyAlias")
                keyPassword = signingValue("TEST_KEY_PASSWORD", "owntv.testKeyPassword")
            }
        }
    }

    testOptions {
        // JVM unit tests reach android.util.Log / SystemClock through core; return defaults instead
        // of "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            // Pseudolocales (en-XA / ar-XB) are the layout-stress instrument: they lengthen every
            // string and mirror the layout, so an overflowing screen shows up before a translator
            // ever sees it. localeFilters below would otherwise strip them, so the debug-only
            // qualifiers are added back through the per-variant API in the androidComponents block.
            isPseudoLocalesEnabled = true
            if (testKeystore != null) {
                signingConfig = signingConfigs.getByName("test")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Three shipped languages: English, Simplified and Traditional Chinese. core still carries its
        // 27 packaged locales in the dependency, but nothing outside this list enters the APK — and no
        // later step can remove a locale either, because shrinkResources drops unreferenced resources,
        // never locales. These are resource qualifiers, NOT runtime BCP-47 tags; the matching tags live
        // in AppLocales, and the two lists must be edited together. Debug pseudolocales are added back
        // per variant below; release ships neither.
        localeFilters.addAll(listOf("en", "zh-rCN", "zh-rTW"))
    }

    packaging {
        jniLibs {
            // Every .so here is an already-stripped prebuilt from a dependency, so AGP's strip step
            // has nothing to remove and merely fails loudly on a machine with no NDK.
            keepDebugSymbols += "**/*.so"
        }
    }

    lint {
        // CI gates on this (see .github/workflows/android.yml), so an error must mean something.
        abortOnError = true
        warningsAsErrors = false
        // A counted sentence must use Android plural resources; keep this invariant fatal so a new
        // extraction cannot reintroduce English-only quantity wording. The strings live in core, but
        // the call site that needs a plural is here.
        fatal += "PluralsCandidate"
        // Media3's player API surface is almost entirely @UnstableApi; this app is built on core's
        // player, so the check fires ~30 times across the player, Live and Multiview code and
        // carries no signal. Opting in file-by-file would only move the same acknowledgement into a
        // handful of annotations. The television app disables it for the same reason.
        disable += "UnsafeOptInUsageError"
        // local.properties is developer-local and never committed (its Windows SDK path cannot be
        // escaped without breaking the local tooling that writes it). CI has no such file at all.
        disable += "PropertyEscape"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Re-add the debug-only pseudolocale qualifiers that the shared `localeFilters` set above would
// otherwise strip. This is the per-variant SetProperty form (the androidResources block sets the
// MutableSet extension form, which applies to every variant equally and so cannot keep
// pseudolocales out of release). Release variants ship neither pseudolocale.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidResources.localeFilters.addAll("en-rXA", "ar-rXB")
    }
}

// A baseline profile is a list of code paths, not machine code, so one recording serves both ABI
// flavors. `mergeIntoMain` writes it to `src/main/generated/baselineProfiles/` rather than the
// recording flavor's own source set, so a profile recorded on an arm phone also ships in the
// x86_64 APK — and so there is one file to review in a diff instead of two.
baselineProfile {
    mergeIntoMain = true
}

// --- hardcoded-literal gate ----------------------------------------------------------------
//
// The same check CI runs, moved onto the developer's own machine. CI is still the enforcing gate —
// this only makes the failure arrive seconds after writing the string instead of minutes after
// pushing it. Wired in while this app is still empty, on purpose: retrofitting it onto twenty
// finished screens is a far worse job.
//
// Deliberately NOT offered: any flag that records the literal and turns the build green. A red build
// means the string moves into core's strings_*.xml or is declared technical — those are the only two
// exits.
abstract class VerifyI18nLiterals : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSources: ConfigurableFileCollection

    /** The checker and its two reviewed manifests: edit any of them and the verdict may change. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolInputs: ConfigurableFileCollection

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    private fun interpreter(): String? = listOf("python", "python3").firstOrNull { candidate ->
        runCatching {
            execOps.exec {
                commandLine(candidate, "--version")
                isIgnoreExitValue = true
                standardOutput = ByteArrayOutputStream()
                errorOutput = ByteArrayOutputStream()
            }.exitValue == 0
        }.getOrDefault(false)
    }

    @TaskAction
    fun verify() {
        val python = interpreter()
        if (python == null) {
            // Failing here would block anyone without Python from building at all. Warn loudly
            // instead — CI still enforces it, so the worst case is a late failure, not a missed one.
            logger.warn(
                "\n  WARNING: Python was not found, so the hardcoded-text check did not run." +
                    "\n  Install Python 3 to catch untranslatable text before pushing; CI will still catch it.\n",
            )
            stamp.get().asFile.writeText("skipped: no python interpreter\n")
            return
        }
        val output = ByteArrayOutputStream()
        val result = execOps.exec {
            workingDir = repoRoot.get().asFile
            commandLine(python, "tools/i18n/check_hardcoded_strings.py", "verify", "--bootstrap")
            environment("PYTHONIOENCODING", "utf-8")
            isIgnoreExitValue = true
            standardOutput = output
            errorOutput = output
        }
        if (result.exitValue != 0) {
            logger.error(output.toString(Charsets.UTF_8))
            throw GradleException("Hardcoded text check failed — see the report above.")
        }
        stamp.get().asFile.writeText("ok\n")
    }
}

val verifyI18nLiterals = tasks.register<VerifyI18nLiterals>("verifyI18nLiterals") {
    group = "verification"
    description = "Fails the build on user-visible text left hardcoded in Kotlin."
    // :app is the only module in this repo. Core's own Kotlin is gated by the identical task in the
    // core repo, so a literal cannot escape by moving between the two. A new module here needs a
    // line added HERE as well as in check_hardcoded_strings.py's SRC_ROOTS — an undeclared input
    // silently skips the gate whenever only that module changed.
    kotlinSources.from(fileTree("src/main/java") { include("**/*.kt") })
    toolInputs.from(
        rootProject.file("tools/i18n/check_hardcoded_strings.py"),
        rootProject.file("tools/i18n/hardcoded_baseline.txt"),
        rootProject.file("tools/i18n/safe_literals.txt"),
    )
    repoRoot.set(rootProject.layout.projectDirectory)
    stamp.set(layout.buildDirectory.file("i18n/literal-inventory.txt"))
}

// preBuild fronts every variant, so debug compile checks and release assembles are both covered.
// Inputs are declared above, so an unchanged source tree makes this UP-TO-DATE and free.
tasks.named("preBuild") { dependsOn(verifyI18nLiterals) }

dependencies {
    // The shared engine, built from vendor/OwnTV_Core (see settings.gradle.kts). Gradle substitutes
    // these coordinates with that build's modules; the version in the catalog is only a placeholder.
    implementation(libs.owntv.core)
    // The shared playback engine. It renders nothing itself, which is exactly why the same engine
    // serves the TV HUD and this app's touch controls. Always on core's version.
    implementation(libs.owntv.player.core)
    // libmpv is NOT declared here. `:player-core` exposes it as `api`, so it arrives on this app's
    // compile classpath with core — which it must, because `OwnTVPlayer`'s supertype is
    // `MPVLib.EventObserver`. Core's catalog owns the version, and two hosts each naming their own
    // is how a packaging conflict over a native library starts.

    // Core
    implementation(libs.androidx.core.ktx)
    // The launch screen: the flip-card animation, one theme per icon colour (see themes.xml).
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose (BOM-managed) — Material 3 for touch. Never androidx.tv.*; see libs.versions.toml.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    // Preview annotations only: @Preview appears nowhere in main, so this stays out of release.
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // Window size classes — this app has to lay out for a phone and a tablet from one build.
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.graphics.shapes)

    // Lifecycle / Navigation
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // WorkManager — core's sync/EPG workers, whose auto-initializer core's manifest removes.
    implementation(libs.androidx.work.runtime)

    // Paging — core's catalog DAOs return PagingSource.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Image loading
    // Media3's SubtitleView, for the image subtitles the engine renders through ExoPlayer.
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // TVbox VOD: config parsing (kotlinx-serialization JSON tree API, runtime only - no plugin,
    // see libs.versions.toml) and jar-spider result parsing (Gson reflection in JarResultParser).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    // TVbox JS (drpy2) spider: QuickJS runtime + the jQuery-flavoured html selector helpers.
    implementation(libs.quickjs.android)
    implementation(libs.jsoup)
    testImplementation(libs.quickjs.java)
    // TVbox ported-chain tests: CMS header/URL behaviour against a local server.
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // SAF tree traversal for TVbox local sources (clan://localhost/ granted directory).
    implementation(libs.androidx.documentfile)

    // Casting. The sender half only: the Chromecast decodes the stream itself, so nothing of the
    // playback engine crosses over — see tv.own.owntv.mobile.cast.
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)

    // Local sync pairing: the camera reads the QR code the other device shows. zxing decodes it —
    // the same library core uses to draw one.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Dependency injection
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Debug tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Records the profile this module then packages. Recording needs a real device, so this is
    // never exercised by CI; see baselineprofile/BaselineProfileGenerator.kt.
    baselineProfile(project(":baselineprofile"))
}
