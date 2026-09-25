# ============================================================
# Hermex Android App — ProGuard / R8 Rules
# ============================================================
# Package: com.hermex.android
# Libraries: Hilt 2.51.1, Retrofit 2.9.0, OkHttp 4.12.0,
#            Room 2.6.1, Coil 2.6.0, kotlinx.serialization,
#            kotlinx.coroutines, Navigation Compose,
#            Compose BOM 2024.02.00
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
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Keep the application class
-keep class com.hermex.android.HermexApplication { *; }

# --- Compose / Navigation Compose ---
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# --- Hilt / Dagger 2.51.1 ---
-dontwarn dagger.**
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$ActivityContextWrapper { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponentManager { *; }
-keep class * extends dagger.hilt.internal.definecomponent.DefineComponentClasses { *; }
-keep class * extends dagger.hilt.internal.aggregatedroot.AggregatedRoot { *; }

# Keep Hilt-generated classes
-keep class * extends dagger.hilt.internal.GeneratedComponentManagerHolder { *; }
-keep class * extends dagger.hilt.internal.TestInjector { *; }
-keep class * extends dagger.hilt.android.internal.managers.HiltWrapper_ActivityRetainedComponentManager_ActivityRetainedComponentBuilderEntryPoint { *; }
-keep class * extends dagger.hilt.android.internal.managers.HiltWrapper_ActivityRetainedComponentManager_ActivityRetainedComponentBuilderEntryPoint { *; }

# Hilt modules and entry points
-keep class * {
    @dagger.hilt.android.internal.lifecycle.HiltViewModelMap <fields>;
}
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.internal.lifecycle.HiltViewModelMap class * { *; }
-keep @dagger.hilt.components.SingletonComponent class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.Provides class * { *; }
-keep @dagger.Binds class * { *; }
-keep @hilt.android.AndroidEntryPoint class * { *; }

# --- Retrofit 2.9.0 ---
-keepattributes Exceptions, Signature, InnerClasses
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Keep Retrofit API interfaces
-keep,allowobfuscation interface com.hermex.android.data.remote.** {
    @retrofit2.http.* <methods>;
}

# --- OkHttp 4.12.0 ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room 2.6.1 ---
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

# Keep Room TypeConverters
-keep class * {
    @androidx.room.TypeConverter <methods>;
}

# --- Coil 2.6.0 ---
-dontwarn coil.**
-keep class coil.** { *; }

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hermex.android.**$$serializer { *; }
-keepclassmembers class com.hermex.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.hermex.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable classes
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

# --- Kotlin ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# --- Hermex App Data Classes ---
# Keep all data/model classes (DTOs, entities, sealed classes)
-keep class com.hermex.android.data.** { *; }
-keep class com.hermex.android.model.** { *; }
-keep class com.hermex.android.domain.** { *; }

# Keep DI modules and component classes
-keep class com.hermex.android.di.** { *; }

# Keep ViewModels
-keep class com.hermex.android.ui.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }

# --- Navigation Compose ---
-dontwarn androidx.navigation.**
-keep class androidx.navigation.** { *; }

# --- Enum classes ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Parcelable ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# --- Serializable ---
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

# --- R8 full mode (keep data classes used by Gson/moshi/serialization) ---
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# --- AndroidX Security Crypto (EncryptedSharedPreferences) ---
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.**
