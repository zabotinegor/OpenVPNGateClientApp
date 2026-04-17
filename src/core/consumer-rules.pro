-keep class de.blinkt.openvpn.** { *; }
-keepclassmembers enum de.blinkt.openvpn.core.ConnectionStatus { *; }
-keep class de.blinkt.openvpn.core.IOpenVPNServiceInternal { *; }

# Preserve generic signatures and runtime annotations required by Retrofit
# to resolve suspend return types on minified release builds.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# Keep Retrofit service interfaces and HTTP-annotated methods.
-keep,allowobfuscation interface * {
	@retrofit2.http.* <methods>;
}

# Keep Gson TypeToken metadata used for reflective generic resolution.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Keep fields annotated with SerializedName for reflective Gson mapping.
-keepclassmembers class * {
	@com.google.gson.annotations.SerializedName <fields>;
}
