# Keep Vouchflow public API classes — required for reflection in host apps that use R8 full mode.
-keep class dev.vouchflow.sdk.Vouchflow { *; }
-keep class dev.vouchflow.sdk.VouchflowInstance { *; }
-keep class dev.vouchflow.sdk.VouchflowConfig { *; }
-keep class dev.vouchflow.sdk.VouchflowError { *; }
-keep class dev.vouchflow.sdk.VouchflowResult { *; }
-keep class dev.vouchflow.sdk.FallbackResult { *; }
-keep class dev.vouchflow.sdk.FallbackVerificationResult { *; }
-keep class dev.vouchflow.sdk.VouchflowSignals { *; }
-keep class dev.vouchflow.sdk.FallbackSignals { *; }
-keep enum dev.vouchflow.sdk.** { *; }

# Keep Gson model classes — field names must survive shrinking for JSON deserialization.
-keep class dev.vouchflow.sdk.network.models.** { *; }
-keepclassmembers class dev.vouchflow.sdk.network.models.** { *; }

# Keep AccountManager authenticator service — declared in merged manifest.
-keep class dev.vouchflow.sdk.internal.VouchflowAuthenticatorService { *; }
-keep class dev.vouchflow.sdk.internal.VouchflowInitProvider { *; }
