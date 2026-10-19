# ============================================================
# Hermex Android App — ProGuard / R8 Rules
# ============================================================
# Application id / namespace : com.hermex.v3
# Library modules            : com.hermex.core.network, com.hermex.core.data
# Libraries actually on the release runtime classpath (verified with
#   ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath):
#   OkHttp 4.12 + okio, Room 2.6.1, Coil, androidx.security-crypto (Tink),
#   kotlinx.serialization-json, kotlinx.coroutines, Compose/Navigation, Gson 2.8.9 (transitive).
# NOT present: Hilt/Dagger, Retrofit, Moshi — their keep blocks were removed for that reason.
#
# WHY THIS FILE WAS REWRITTEN (audit finding #21, Phase 7): every package-scoped rule in the
# previous version named `com.hermex.android.*`, which is the frozen v2 package. This app is
# `com.hermex.v3` with DTOs in `com.hermex.core.*`, so those rules matched nothing:
#   - `-keep class com.hermex.android.HermexApplication { *; }`         -> inert
#   - `...data.remote.**` Retrofit interface keeps                     -> inert (Retrofit absent)
#   - `com.hermex.android.**$$serializer` / Companion / serializer()   -> inert
#   - blanket keeps for `.data.** .model.** .domain.** .di.** .ui.**`  -> inert
# The published release builds still worked because PACKAGE-INDEPENDENT rules happened to cover the
# same ground (`-keep @kotlinx.serialization.Serializable class *`, `-keep class * extends ViewModel`,
# Room's annotation-driven keeps). That is luck, not design: any DTO outside those annotations, or a
# future cleanup of the dead v2 package name, would silently change what R8 keeps.
# Everything below either names a real package or is package-independent by intent. Nothing that was
# effective before was narrowed, so this change can only add coverage — never remove it.
# Verification: release build + mapping.txt inspection (see CHANGELOG for this version).
# ============================================================

# --- General Android ---
-keepattributes Signature
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# --- App entry point named by AndroidManifest.xml ---
# AGP generates keeps for manifest-declared classes; this one is explicit so that renaming or moving
# the Application subclass stays a deliberate act rather than something R8 discovers at shrink time.
-keep class com.hermex.v3.HermexApplication { *; }

# --- Compose / Navigation Compose ---
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }
-dontwarn androidx.navigation.**
-keep class androidx.navigation.** { *; }

# --- OkHttp 4.12.0 (+ okio, optional JSSE providers) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room 2.6.1 (core/data: Session, Message, CachedMessage, ToolCall, ThinkingCard + DAOs) ---
-dontwarn androidx.room.paging.**
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class * {
    @androidx.room.TypeConverter <methods>;
}

# --- Coil ---
-dontwarn coil.**
-keep class coil.** { *; }

# --- kotlinx.serialization ---
# The plugin ships base rules for kotlinx.serialization itself; the package-scoped rules below are
# the ones that used to name the wrong package and therefore did nothing.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Generated serializers and companions for OUR DTOs (RPC frames in core/network, cached models in
# core/data/models). Without these, R8 full mode can drop a generated `$$serializer` or the
# `Companion` that `Json.decodeFromString(...)` resolves reflectively.
-keep,includedescriptorclasses class com.hermex.core.network.**$$serializer { *; }
-keep,includedescriptorclasses class com.hermex.core.data.models.**$$serializer { *; }
-keepclassmembers class com.hermex.core.network.** {
    *** Companion;
}
-keepclassmembers class com.hermex.core.data.models.** {
    *** Companion;
}
-keepclasseswithmembers class com.hermex.core.network.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.hermex.core.data.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Package-independent backstop — this is the rule that has been carrying every release build so far,
# kept on purpose so correcting the package names above cannot reduce what is kept.
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# --- kotlinx.coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# --- Kotlin metadata ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# --- ViewModels (instantiated through androidx.lifecycle reflection) ---
-keep class * extends androidx.lifecycle.ViewModel { *; }

# --- Gson 2.8.9 (present transitively in the release runtime graph) ---
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# --- Enums / Parcelable / java.io.Serializable ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- AndroidX Security Crypto (EncryptedSharedPreferences used by SecureStore) ---
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.**
