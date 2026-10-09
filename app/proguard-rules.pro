# PDFBox lädt Klassen/Ressourcen teils per Reflection -> vollständig behalten
-keep class com.tom_roush.** { *; }
-keep class org.bouncycastle.** { *; }

# Optionale Abhängigkeiten, die auf Android fehlen
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn com.tom_roush.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.slf4j.**
