# Handheld Forge uses no reflection or serialization frameworks; defaults are enough.

# commons-compress: only bzip2 is used; optional codecs it can link to aren't bundled.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn java.lang.invoke.**

# Muninn: zxing is used directly; nothing reflective.
-dontwarn com.google.zxing.**

# WorkManager creates the inbox worker by name.
-keep class io.github.ravenor907.muninn.net.InboxWorker { *; }
