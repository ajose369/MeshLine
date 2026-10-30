# ---------------------------------------------------------------------------
# JNI bridge
#
# R8 must not rename or strip anything the native library resolves by name.
# The Rust side exports symbols as Java_org_meshline_app_bridge_MeshCoreBridge_*,
# so the class name, the package, and every native method signature are part of
# the ABI. Renaming any of them produces UnsatisfiedLinkError at runtime only,
# which a debug build would never catch.
# ---------------------------------------------------------------------------
-keep class org.meshline.app.bridge.MeshCoreBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Types crossing the JNI boundary are constructed reflectively by name.
-keep class org.meshline.app.db.MessageEntity { *; }
-keep class org.meshline.app.db.ResourcePinEntity { *; }
-keep class org.meshline.app.db.PeerNodeEntity { *; }

# ---------------------------------------------------------------------------
# Application entry points
# ---------------------------------------------------------------------------
-keep class org.meshline.app.MeshLineApplication { *; }
-keep class org.meshline.app.service.MeshRelayService { *; }

# ---------------------------------------------------------------------------
# Kotlin / AndroidX
# ---------------------------------------------------------------------------
-keepclassmembers class ** {
    @kotlinx.coroutines.* <methods>;
}
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# Keep line numbers so Play Console crash reports stay readable after
# deobfuscation with the uploaded mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
