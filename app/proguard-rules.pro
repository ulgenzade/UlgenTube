# UlgenTube ProGuard / R8 Kuralları

# youtubedl-android kütüphanesi ve Jackson/JSON modelleri
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class com.yausername.common.** { *; }
-dontwarn com.yausername.**

# Jackson ve reflection kullanan modeller
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Uygulama veri modelleri ve Room varlıkları
-keep class com.ulgentube.app.domain.model.** { *; }
-keep class com.ulgentube.app.data.local.** { *; }

# OkHttp ve Jsoup uyarılarını bastır
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
