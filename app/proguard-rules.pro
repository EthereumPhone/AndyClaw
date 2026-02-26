# ── AndyClaw ProGuard / R8 rules ─────────────────────────────────────

# Keep line numbers for crash reporting stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ────────────────────────────────────────────
# The serialization plugin generates companion serializer() methods and
# $serializer inner classes that R8 must not strip or rename.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.ethereumphone.andyclaw.**$$serializer { *; }
-keepclassmembers class org.ethereumphone.andyclaw.** {
    *** Companion;
}
-keepclasseswithmembers class org.ethereumphone.andyclaw.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Room (entities, DAOs, database classes) ──────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers @androidx.room.Entity class * { *; }

# ── OkHttp ───────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ── Tinfoil bridge (Go AAR — JNI) ───────────────────────────────────
-keep class tinfoilbridge.** { *; }

# ── Llamatik (KMP llama.cpp wrapper — JNI) ──────────────────────────
-keep class com.llamatik.** { *; }

# ── EthereumPhone SDKs ──────────────────────────────────────────────
-keep class com.aspect.** { *; }
-keep class org.ethereumphone.walletsdk.** { *; }
-keep class org.ethereumphone.contactssdk.** { *; }
-keep class org.ethereumphone.messengersdk.** { *; }

# ── BeanShell (interpreter uses reflection) ──────────────────────────
-keep class bsh.** { *; }
-dontwarn java.applet.**
-dontwarn java.awt.**
-dontwarn javax.script.**
-dontwarn javax.servlet.**
-dontwarn javax.swing.**
-dontwarn org.apache.bsf.**

# ── gplayapi (Aurora Store integration) ──────────────────────────────
-keep class com.aurora.gplayapi.** { *; }

# ── Reflection call targets ──────────────────────────────────────────
# PackageManagerSkill reflects on hidden Android APIs
-keep class android.content.pm.IPackageDataObserver { *; }
-keep class android.content.pm.IPackageDataObserver$Stub { *; }
# HeartbeatBindingService reflects on PaymasterProxy
-dontwarn android.os.PaymasterProxy

# ── Enums (used in serialization and Room converters) ────────────────
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── AIDL interfaces (bound services) ────────────────────────────────
-keep class org.ethereumphone.andyclaw.ipc.IHeartbeatService { *; }
-keep class org.ethereumphone.andyclaw.ipc.IHeartbeatService$* { *; }
-keep class org.ethereumphone.andyclaw.IAndyClawSkill { *; }
-keep class org.ethereumphone.andyclaw.IAndyClawSkill$* { *; }

# ── Strip verbose logging in release ─────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ── dnsjava ──────────────────────────────────────────────────────────
-dontwarn org.xbill.DNS.**
-keep class org.xbill.DNS.** { *; }

# ── Protobuf (transitive from MessengerSDK / XMTP) ──────────────────
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ── Ktor (embedded server for CLI access) ────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
