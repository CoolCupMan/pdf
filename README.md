# PDF Extraktor (Android)

Extrahiert einen Seitenbereich aus einem (zusammengeführten) PDF – als neues PDF oder als TXT.

## Eingaben

| Feld | Beispiele | Bedeutung |
|---|---|---|
| **Von** | `56` · `Kapitel 2` · `"56"` | Startseite als Zahl, oder erste Seite, die den Text enthält. Leer = Seite 1. Zahl in Anführungszeichen wird als Text gesucht. |
| **Bis** | `98` · `Kapitel 3` · `25/100` · leer | Endseite als Zahl, oder erste Seite **nach der Startseite**, die den Text enthält. Leer = bis zum Ende. |
| **Prozentual bis** | `100/100` · `75/100` · `50/100` · `25/100` · `25/200` · `25%` | Anteil des ganzen Dokuments: bei 1000 Seiten ist `25/100` = bis Seite 250. Hat Vorrang vor „Bis“. |
| ☑ **Automatisch bis zum Ende** | | Hat Vorrang vor allen End-Feldern. |
| ☑ **Seite mit End-Text einschließen** | | Aus: die Seite vor dem End-Treffer ist die letzte Seite. |

Die Textsuche ignoriert Groß-/Kleinschreibung und Mehrfach-Leerzeichen/Zeilenumbrüche.
Gescannte PDFs ohne Textebene können nicht per Text durchsucht werden (Seitenzahlen/Prozent funktionieren trotzdem).

## Version & parallele Installation

Version = `appVersionBase` (in `gradle.properties`) + Build-Nummer, z. B. `1.0.7`.
Jede Version bekommt eine eigene Application-ID, z. B. `com.coolcupman.pdfextractor.v1_0_7`,
und einen eigenen App-Namen („PDF Extraktor 1.0.7“) – dadurch können mehrere Versionen gleichzeitig installiert sein.

## APK bekommen

Jeder Push baut über GitHub Actions eine APK und hängt sie an ein Release `v<Version>` (Reiter *Releases*) bzw. als Artifact an den Workflow-Lauf.

Lokal (Android SDK nötig):

```
./gradlew assembleRelease -PbuildNumber=7
# -> app/build/outputs/apk/release/PdfExtraktor-1.0.7-release.apk
```
