# Kotlinx Serialization (модели сохранений в domain.model и data.db)
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.ayaks.winterforest.**$$serializer { *; }
-keepclassmembers class com.ayaks.winterforest.** {
    *** Companion;
}
-keepclasseswithmembers class com.ayaks.winterforest.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }

# Yandex Mobile Ads SDK (потребительские правила поставляются AAR-архивом mobileads,
# дополнительная защита публичных колбэков и опциональных классов от R8 full mode)
-keep class com.yandex.mobile.ads.** { *; }
-dontwarn com.yandex.mobile.ads.**
-dontwarn com.huawei.hms.**
-dontwarn com.hihonor.**
-dontwarn com.xiaomi.**
-dontwarn io.appmetrica.**
-dontwarn org.conscrypt.**
