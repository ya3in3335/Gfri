# Green Accesse R8 rules

# kotlinx.serialization: keep generated serializers for protocol / storage models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.greenaccesse.app.**$$serializer { *; }
-keepclassmembers class com.greenaccesse.app.** { *** Companion; }
-keepclasseswithmembers class com.greenaccesse.app.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
