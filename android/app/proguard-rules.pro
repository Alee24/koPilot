-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep class ke.co.kopilot.core.network.dto.** { *; }
-dontwarn org.webrtc.**
-keep class org.webrtc.** { *; }
