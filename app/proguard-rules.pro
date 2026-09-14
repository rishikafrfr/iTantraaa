# sherpa-onnx JNI accesses Kotlin data-class fields by name — keep everything
-keep class com.k2fsa.sherpa.onnx.** { *; }

# kotlinx.coroutines / Compose are R8-aware; no extra rules needed.
# org.json is part of the Android platform.
