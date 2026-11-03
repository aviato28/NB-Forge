# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.namanbansal.forge.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.namanbansal.forge.**$$serializer { *; }

# PDFBox-Android: optional JPEG2000 codec is not bundled (JPX images are skipped gracefully).
-dontwarn com.gemalto.jp2.**
# PDFBox loads fonts, filters and resources reflectively; keep it intact.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
