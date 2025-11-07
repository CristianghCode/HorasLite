# Keep ThreeTen Android Backport classes and zone data to avoid runtime
# crashes in release builds when R8 removes required metadata.
-keep class org.threeten.bp.** { *; }
-dontwarn org.threeten.bp.**
-keep class org.threeten.extra.** { *; }
-keep class org.threeten.bp.zone.** { *; }
-keep class com.jakewharton.threetenabp.** { *; }
