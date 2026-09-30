# Consumer ProGuard rules for the mcp-server module.
# Keep Ktor and MCP SDK reflection-using types.

-keep class io.ktor.** { *; }
-keep class io.modelcontextprotocol.kotlin.sdk.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.aura.mcp.**$$serializer { *; }
-keepclassmembers class com.aura.mcp.** { *** Companion; }
-keepclasseswithmembers class com.aura.mcp.** { kotlinx.serialization.KSerializer serializer(...); }
