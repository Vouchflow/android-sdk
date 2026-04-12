# Keep Vouchflow public API classes — required for reflection in host apps that use R8 full mode.
-keep class com.vouchflow.sdk.Vouchflow { *; }
-keep class com.vouchflow.sdk.VouchflowInstance { *; }
-keep class com.vouchflow.sdk.VouchflowConfig { *; }
-keep class com.vouchflow.sdk.VouchflowError { *; }
-keep class com.vouchflow.sdk.VouchflowResult { *; }
-keep class com.vouchflow.sdk.FallbackResult { *; }
-keep class com.vouchflow.sdk.FallbackVerificationResult { *; }
-keep class com.vouchflow.sdk.VouchflowSignals { *; }
-keep class com.vouchflow.sdk.FallbackSignals { *; }
-keep enum com.vouchflow.sdk.** { *; }

# Keep Gson model classes — field names must survive shrinking for JSON deserialization.
-keep class com.vouchflow.sdk.network.models.** { *; }
-keepclassmembers class com.vouchflow.sdk.network.models.** { *; }

# Keep AccountManager authenticator service — declared in merged manifest.
-keep class com.vouchflow.sdk.internal.VouchflowAuthenticatorService { *; }
-keep class com.vouchflow.sdk.internal.VouchflowInitProvider { *; }
