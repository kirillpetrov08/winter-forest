# Kotlinx Serialization (модели сохранений в Room)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.ayaks.winterforest.data.**$$serializer { *; }
-keepclassmembers class com.ayaks.winterforest.data.** {
    *** Companion;
}

# Room entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Yandex Mobile Ads SDK (потребительские правила поставляются AAR-архивом mobileads,
# дополнительная защита публичных колбэков от агрессивного R8)
-keep class com.yandex.mobile.ads.** { *; }
-dontwarn com.yandex.mobile.ads.**
