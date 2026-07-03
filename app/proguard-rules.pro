# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.questionsolver.app.**$$serializer { *; }
-keepclassmembers class com.questionsolver.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.questionsolver.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
