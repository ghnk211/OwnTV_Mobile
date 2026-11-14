# OwnTV release keep rules (R8). Each rule guards a runtime lookup that R8 can't see
# statically — trim only with a full on-device regression (sync, both engines, backup).

# --- Crash reports: keep readable stack traces (mapping.txt still needed for names) ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- libmpv JNI: native code resolves MPVLib and its event callbacks by name ---
-keep class dev.jdtech.mpv.** { *; }

# --- WorkManager: workers are re-instantiated by FQCN string (KoinWorkerFactory matches
#     workerClassName against ::class.java.name, and WorkManager persists the name in its DB
#     across app updates) ---
-keep class * extends androidx.work.ListenableWorker

# --- Room: Room.getGeneratedImplementation() derives this exact class name and loads it
#     reflectively. Keep the generated database entry point in optimized release builds. ---
-keep class tv.own.owntv.core.database.OwnTVDatabase_Impl { *; }

# --- Persisted enum names: DataStore prefs, backup JSON ("t" = MediaType.name), and
#     per-setting modes (ZoomMode, ThemeMode, StartupMode, AnimationLevel, ResumeMode,
#     EpgAutoRefresh, PlaylistAutoRefresh, ...) round-trip through Enum.name/valueOf.
#     Renaming a constant would silently reset settings and break old backups. ---
-keep enum tv.own.owntv.** { *; }

# --- Cast: the SDK reads the OptionsProvider's class name out of the manifest and instantiates it
#     reflectively, so neither the name nor the no-argument constructor may be touched. ---
-keep class tv.own.owntv.mobile.cast.CastOptionsProvider { *; }

# --- TVbox jar spider (DexClassLoader): a jar's Spider class is loaded by name and its result
#     beans are read via Gson reflection, so neither the catvod stubs nor the jar-facing API
#     surface may be renamed or stripped. The stubs are a hard ABI contract with third-party
#     jars compiled against com.github.catvod.crawler.Spider. ---
-keep class com.github.catvod.** { *; }
-keep class tv.own.owntv.mobile.tvbox.engine.** { *; }
-keep class tv.own.owntv.mobile.tvbox.config.model.** { *; }
# --- TVbox JS (drpy) spider: the QuickJS wrapper reaches its native/reflective surface by name.
#     The AAR ships its own consumer rules; this only silences the optional surface this app
#     does not call (wrapper-java is test-only). Release still needs one on-device JS-source play. ---
-dontwarn com.whl.quickjs.**
-keepclassmembers class * implements android.webkit.WebViewClient { *; }

# --- TVbox Python (Chaquopy) spiders: Python imports PyHost by fully-qualified name, so the
#     bridge may never be renamed or stripped. The broader engine.** rule above already covers
#     it; this is the explicit second line of defence the py migration plan calls for. ---
-keep class tv.own.owntv.mobile.tvbox.engine.py.** { *; }
-dontwarn com.chaquo.python.**
