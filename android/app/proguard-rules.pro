# Keep MeshLine JNI Bridge methods
-keep class org.meshline.app.bridge.MeshCoreBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep MapLibre and Room DB models
-keep class org.meshline.app.gis.** { *; }
-keep class org.meshline.app.transport.** { *; }
