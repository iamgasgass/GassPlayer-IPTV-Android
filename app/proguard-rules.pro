-keep class com.gassplayer.android.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn org.conscrypt.**
# libVLC: classi e campi letti/scritti dal codice nativo (JNI) per nome.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
# Decoder FFmpeg (NextLib) per Media3: metodi nativi e classi usate via JNI.
-keep class io.github.anilbeesetti.nextlib.** { *; }
-dontwarn io.github.anilbeesetti.nextlib.**
