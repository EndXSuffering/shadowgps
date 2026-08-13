# kotlinx.serialization keeps its generated serializers on the companion of each
# @Serializable class; R8 cannot see they are used and would strip them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class dev.shadowgps.**$$serializer { *; }
-keepclassmembers class dev.shadowgps.** {
    *** Companion;
}
-keepclasseswithmembers class dev.shadowgps.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# osmdroid reflects over tile source names and its own configuration.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# OkHttp ships optional platform integrations that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
