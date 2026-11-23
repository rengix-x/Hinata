# Hinata ProGuard / R8 Rules
# Aggressive DEX optimization for minimal APK footprint
-repackageclasses 'o'
-allowaccessmodification
-mergeinterfacesaggressively

# Hide original Kotlin/Java source file names & debug attributes in release stack traces
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*

# Strip all android.util.Log calls in release builds for zero overhead and smaller DEX.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep AndroidManifest components explicitly
-keep class com.edgeflow.service.EdgeAccessibilityService { <init>(); }
-keep class com.edgeflow.data.EdgeConfigProvider { <init>(); }
-keep class com.edgeflow.settings.SettingsActivity { <init>(); }
-keep class com.edgeflow.service.FloatingProxyActivity { <init>(); }

# Keep Shizuku AIDL interface & UserService entry points (AAR consumer rules handle Shizuku internals)
-keep class com.edgeflow.service.shizuku.INavBarControlService { *; }
-keep class com.edgeflow.service.shizuku.INavBarControlService$Stub { *; }
-keep class com.edgeflow.service.shizuku.NavBarUserService {
    public <init>();
    public <init>(android.content.Context);
}
-dontwarn dev.rikka.shizuku.**
-dontwarn rikka.shizuku.**
-dontwarn org.lsposed.hiddenapibypass.**

# Reflective targets for non-root Circle to Search invocation
-dontwarn android.os.ServiceManager
-dontwarn com.android.internal.app.IVoiceInteractionManagerService
-dontwarn com.android.internal.app.IVoiceInteractionManagerService$Stub
-keep class com.edgeflow.service.CircleToSearchTrigger {
    public static *** trigger(...);
}
-keepclassmembers class * {
    *** showSessionFromSession(...);
}

# Reflective targets for HyperOS / Android Freeform & Control Center floating-window macro
-dontwarn android.app.ActivityOptions
-dontwarn android.app.ActivityThread
-dontwarn android.app.ActivityTaskManager
-dontwarn android.app.MiuiFreeFormManager
-dontwarn miui.app.MiuiFreeFormManager
-dontwarn miui.app.MiuiFreeFormManager$**
-dontwarn android.util.MiuiMultiWindowUtils
-dontwarn miui.util.MiuiMultiWindowUtils
-dontwarn android.util.MiuiMultiWindowAdapter
-keepclassmembers class android.app.ActivityOptions {
    *** setLaunchWindowingMode(int);
}
-keepclassmembers class * {
    *** getActivityOptions(...);
    *** startSmallFreeformForControlCenter(...);
    *** startSmallFreeform(...);
    *** startSmallFreeformVersion2(...);
    *** startSmallFreeformForRecent(...);
    *** getSmallFreeformRect(...);
    *** getAllFreeFormStackInfosOnDisplay(...);
    *** addMiuiFlags(int);
    *** setMiuiFlags(int);
}

# ML Kit: rely on official AAR consumer-proguard-rules so R8 strips unused code safely
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
-keepclassmembers class com.edgeflow.data.MlKitSafeInit {
    public static *** ensureInitialized(...);
}
