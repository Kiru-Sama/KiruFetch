# ---- 通用 ----
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留行号，崩溃栈可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 数据类（SQLite 手工映射，暂不依赖反射，但保留以防万一）----
-keep class com.novafetch.app.data.** { *; }

# ---- WebView JS 接口 ----
# addJavascriptInterface 暴露的方法名不能被混淆，否则 JS 调不到
-keepclassmembers class com.novafetch.app.sniff.MediaBridge {
    public *;
}
-keep class com.novafetch.app.sniff.MediaBridge { *; }

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Kotlin 协程 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- 枚举 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 序列化（如后续引入 Gson）----
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keep @androidx.annotation.Keep class * {*;}
