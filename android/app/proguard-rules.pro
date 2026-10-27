# Muninn: zxing is used directly; nothing reflective.
-dontwarn com.google.zxing.**

# Tor: the native library reads TorService's fields and calls its methods by name.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn org.torproject.jni.**
-dontwarn net.freehaven.tor.control.**

# WorkManager creates the inbox worker by name.
-keep class io.github.ravenor907.muninn.net.InboxWorker { *; }
