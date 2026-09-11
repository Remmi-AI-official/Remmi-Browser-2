# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Preserve package names for GeckoView, SnakeYAML and Remmi reflection
-keeppackagenames org.mozilla.**
-keeppackagenames org.yaml.**
-keeppackagenames com.remmi.**
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions, SourceFile, LineNumberTable

# ==============================================================================
# GECKOVIEW CORE PRESERVATION (MANDATORY FOR GECKO ENGINE)
# ==============================================================================
-keep class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep interface org.mozilla.gecko.** { *; }
-keep class org.mozilla._uniffi.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla._uniffi.**

# Preserve Gecko internal utilities and DebugConfig
-keep class org.mozilla.gecko.util.** { *; }
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# Preserve JNI callbacks between C++ (libxul.so / Rust) and Java
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclasseswithmembernames class org.mozilla.** {
    native <methods>;
}
-keepclassmembers class org.mozilla.gecko.** {
    public *;
    protected *;
}
-keepclassmembers class org.mozilla.geckoview.** {
    public *;
    protected *;
}

# Preserve Adblock Rust Native Bridge, Engine, and Security
-keep class com.remmi.adblock.** { *; }
-dontwarn com.remmi.adblock.**
-keep class com.remmi.browser.security.** { *; }
-dontwarn com.remmi.browser.security.**
-keep class com.remmi.browser.engine.** { *; }
-dontwarn com.remmi.browser.engine.**
-keep class com.remmi.browser.** { *; }
-dontwarn com.remmi.browser.**

# Preserve View models and state holders used by Compose reflection
-keep class com.remmi.browser.model.** { *; }

# Keep SQLCipher native and internal classes
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.sqlcipher.**

# Tor Android Service
-keep class org.torproject.** { *; }
-dontwarn org.torproject.**
-keep class info.guardianproject.torservices.** { *; }
-dontwarn info.guardianproject.torservices.**

# Room & SQLite
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Moshi / Serialization
-keepclassmembers class * {
    @com.squareup.moshi.* <fields>;
    @com.squareup.moshi.* <methods>;
}
-dontwarn com.squareup.moshi.**

# Bouncy Castle Cryptography (Argon2id, HMAC, AES-GCM)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JSoup Optional RE2/J Dependency
-dontwarn com.google.re2j.**

# Common Android missing classes referenced by transitive dependencies (R8 fix)
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn sun.misc.Unsafe
