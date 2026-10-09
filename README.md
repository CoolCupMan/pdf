# PDF Extraktor (Android)

Extrahiert einen Seitenbereich aus einem (zusammengeführten) PDF – als neues PDF oder als TXT.

## Ablauf

1. **PDF importieren** – Knopf öffnet die Dateiauswahl des Systems (Downloads, Google Files, Drive, SD-Karte).
   Alternativ in Google Files: PDF → *Öffnen mit* oder *Teilen* → PDF Extraktor.
2. **Bereich festlegen**

| Felder | Beispiele | Bedeutung |
|---|---|---|
| **Von / Bis** (Seitenzahl oder Text) | `56` bis `98` · `Kapitel 2` bis `Kapitel 3` | Seitenzahl oder erste Seite mit dem Text. Bis-Text wird erst **nach** der Startseite gesucht. Von leer = Seite 1, Bis leer = Ende. `"56"` in Anführungszeichen = Text. |
| **Von % / Bis %** | `0/100` bis `25/100` · `25/100` bis `50/100` · `75/100` · `25%` | Anteil am ganzen Dokument. Bei 1000 Seiten: `25/100` bis `50/100` = Seiten 251–500. Ausgefüllt haben sie Vorrang vor Von/Bis. |
| ☑ **Automatisch bis zum Ende** | | Hat Vorrang vor allen Bis-Feldern. |
| ☑ **Seite mit End-Text einschließen** | | Aus: die Seite vor dem End-Treffer ist die letzte Seite. |

3. **Exportieren** – Exportordner wählen (wird gemerkt und angezeigt), optional Dateiname,
   dann *PDF exportieren* oder *TXT exportieren*. Ohne Ordner fragt die App nach dem Speicherort.
   Danach: *Öffnen* oder *Teilen*.

Die Textsuche ignoriert Groß-/Kleinschreibung und Zeilenumbrüche. Gescannte PDFs ohne Textebene
können nicht per Text durchsucht werden (Seitenzahl/Prozent funktionieren trotzdem).

Die Oberfläche passt sich an Handy, Tablet, Querformat, Split-Screen und Android 15 (Edge-to-Edge) an.

## Version & parallele Installation

Version = `appVersionBase` (in `gradle.properties`) + Build-Nummer, z. B. `1.1.7`.
Jede Version bekommt eine eigene Application-ID, z. B. `com.coolcupman.pdfextractor.v1_1_7`,
und einen eigenen App-Namen („PDF Extraktor 1.1.7“) – dadurch können mehrere Versionen gleichzeitig installiert sein.

## APK bekommen

Jeder Push baut über GitHub Actions eine APK und hängt sie an ein Release `v<Version>` (Reiter *Releases*) bzw. als Artifact an den Workflow-Lauf.

Lokal (Android SDK nötig):

```
./gradlew assembleRelease -PbuildNumber=7
# -> app/build/outputs/apk/release/PdfExtraktor-1.1.7-release.apk
```
