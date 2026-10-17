# PocketSpectro R8 rules.
#
# Keep the app's own classes and names unobfuscated. Settings, the detection journal and the logged
# CSV files store enum names (display mode, save mode, fire window mode, algorithm, baseline) and read
# them back with valueOf(); renamed constants would break files written by earlier versions.
# The app's own code is small, so this costs almost nothing; the libraries are still shrunk.
-keep class com.rfsat.pocketspectro.** { *; }
-keepnames class com.rfsat.pocketspectro.** { *; }

# Readable stack traces in Play's crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MapLibre, Compose and coroutines ship their own consumer rules.
