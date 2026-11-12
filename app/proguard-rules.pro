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

# ZXing core (QR generation) is plain Java, no reflection; ML Kit and CameraX ship their own consumer rules.
-dontwarn com.google.zxing.**
-keep class com.namanbansal.forge.tools.device.TorchTileService { *; }
-keep class com.namanbansal.forge.tools.util.TimerService { *; }

# Image tools: AndroidSVG parses with reflection-free SAX but references optional classes; ML Kit ships consumer rules.
-dontwarn com.caverock.androidsvg.**
-keep class com.caverock.androidsvg.** { *; }
# Serializable overlay / annotation models are stored as JSON in saved state.
-keep class com.namanbansal.forge.core.image.Mark { *; }
-keep class com.namanbansal.forge.core.image.Mark$* { *; }
-keep class com.namanbansal.forge.core.image.TextBox { *; }
-keep class com.namanbansal.forge.core.image.TextAnchor { *; }
