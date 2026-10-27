# Muninn: zxing is used directly; nothing reflective.
-dontwarn com.google.zxing.**

# WorkManager creates the inbox worker by name.
-keep class io.github.ravenor907.muninn.net.InboxWorker { *; }
