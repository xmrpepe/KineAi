-keep class com.kineai.local.engine.NativeEngine { *; }
-keep class com.kineai.local.engine.NativeEngine$TokenSink { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.eclipse.jgit.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn com.googlecode.javaewah.**
-keep class org.eclipse.jgit.** { *; }

# --- AndCode UI layer ---
-keepattributes *Annotation*,Signature,SourceFile,LineNumberTable
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.errorprone.annotations.**
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.yugahashimoto.andcode.core.api.** { *; }
-keep class com.yugahashimoto.andcode.data.settings.Draft { *; }
-keep class com.kineai.local.data.** { *; }
