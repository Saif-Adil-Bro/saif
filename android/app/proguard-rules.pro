# Keep Gson-serialized models (used directly as Retrofit bodies and Room entities)
-keep class com.saif.mosque.data.model.** { *; }
-keep class com.saif.mosque.data.ledger.PrototypeLedgerDraft { *; }
-keep class com.saif.mosque.data.ledger.PrototypeCorrectionDraft { *; }
-keep class com.saif.mosque.data.invitations.PrototypeInvitation { *; }
-keep class com.saif.mosque.data.invitations.InvitationAddress { *; }
-keep class com.saif.mosque.data.outbox.DirectoryDraft { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Retrofit / OkHttp / Gson
-keep class retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
