# FGaussian — Phase 1: Renderkern

Package `com.dan.fgaussian`, Java 21, keine Fremdbibliotheken.
Stand: 12.09.2026 — kompiliert warnungsfrei bis auf eine `this-escape`-Notiz,
gegen die echte DGM5-Kachel geprüft.

## Was drin ist

| Paket | Klasse | Aufgabe |
|---|---|---|
| `core` | `FSplatCloud` | Splat-Wolke als Struct-of-Arrays, voller 3DGS-Attributsatz inkl. SH-Block |
| `core` | `FQuat` | Quaternion-Hilfen ohne Objekterzeugung |
| `render` | `FCamera` | Orbit-Kamera, Viewmatrix direkt in Bildkonvention |
| `render` | `FSplatSorter` | Radix-Sortierung nach Tiefe über die Float-Bits |
| `render` | `FSplatRenderer` | EWA-Projektion, Kachelbinning, paralleler Rasterisierer |
| `shade` | `FHeightRamp` | Höhenrampe, Voreinstellung `balticCoast()` |
| `source` | `FXyzGridSource` | liest XYZ-Raster, baut Flächen-Splats mit Normalen und Lambert-Schattierung |
| `ui` | `FGaussianView` | Swing-Komponente, rendert in `int[]` eines `TYPE_INT_RGB` |
| `ui` | `FOrbitController` | Maussteuerung: drehen, schieben, zoomen |
| `demo` | `FGaussianDemo` | Demo-Fenster mit Detailstufe, Überhöhung, Statuszeile |
| `demo` | `FSplatSnapshot` | rendert headless in eine PNG-Datei und misst die Bildzeit |

## Start

```
java com.dan.fgaussian.demo.FGaussianDemo
```

Ohne Argument sucht die Demo die größte `.xyz`-Datei unterhalb des
Arbeitsverzeichnisses — im Projektordner also von selbst die Niendorfer Kachel.

Messen ohne Fenster:

```
java com.dan.fgaussian.demo.FSplatSnapshot Mehrfachdownload_XdRqXE_Xrnllv/dgm5_32_618_5984_1_sh_2022.xyz probe.png 1 12
```

## Messwerte

Auf zwei Kernen, 1280 × 720, alle 40 000 Splats sichtbar:

- Wolke bauen: 20–26 ms
- Bild rendern: 45 ms bei formatfüllender Kachel, 74 ms bei nahem Blickwinkel

Die Rasterung skaliert über die Kacheln, der Anteil ist nahezu vollständig
parallel — auf acht Kernen ist entsprechend ein Viertel davon zu erwarten.
Beim Ziehen schaltet `FGaussianView` ohnehin auf 60 % Kantenlänge, also gut ein
Drittel der Pixelarbeit.

## Entscheidungen hinter dem Code

**Struct-of-Arrays statt Splat-Objekten.** Bei 400 000 Splats wären es sonst
400 000 Objektheader; so sind es 14 zusammenhängende `float[]`, die blockweise
aus einem BLOB gefüllt und vom JIT vektorisiert werden können.

**Streuen statt Sammeln beim Rastern.** Die übliche 3DGS-Formulierung lässt
jedes Pixel die ganze Kachelliste durchlaufen — auf der GPU richtig, auf der CPU
ruinös. Hier hält jede Kachel einen 16 × 16-Akkumulationspuffer, und die Splats
schreiben in Tiefenreihenfolge nur in ihre eigene Fläche. Die Arbeit wächst mit
der Summe der Splatflächen statt mit Pixelzahl × Splatzahl.

**Radix statt `Arrays.sort`.** Für positive Floats ist die IEEE-754-Bitfolge
monoton, `floatToRawIntBits` liefert also direkt den Sortierschlüssel. Zwei
16-Bit-Durchgänge, lineare Laufzeit, kein Komparator.

**Viewmatrix in Bildkonvention.** Die Kamera liefert x rechts, y unten, z
vorwärts. Dadurch hat die Jacobimatrix der Projektion kein Vorzeichen, was in
der Kovarianzprojektion eine klassische Fehlerquelle ausschaltet.

**Szene um den Kachelmittelpunkt zentriert.** UTM-Hochwerte um 5 984 000 kosten
in `float` rund 0,5 m Auflösung — genug, um ein 5-m-Raster sichtbar zu stören.

**Der SH-Block ist von Anfang an vorgesehen.** `FSplatCloud.allocateSh(grad)`
reserviert ihn; die Farbe steckt bei Grad 0 vollständig in `cr/cg/cb`. Ein
späterer PLY-Import aus einer Foto-Rekonstruktion braucht damit nur einen Parser
und einen `FSHEvaluator` — am Renderer ändert sich nichts.

## Bekannte Grenzen

- **Projektion läuft einsträngig.** Das Kompaktieren der sichtbaren Splats
  braucht eine fortlaufende Schreibposition. Ab etwa 200 000 Splats lohnt sich
  eine Sichtbarkeitsmaske mit Präfixsumme.
- **Keine richtungsabhängige Farbe.** SH-Auswertung fehlt noch, nur der
  Speicherplatz ist da.
- **Normalen sind ungeglättet.** Sie entstehen aus zentralen Differenzen der
  überhöhten Höhen, weshalb Vegetationsrauschen im DGM als feine Zacken
  durchschlägt. Ein optionaler Glättungsdurchgang wäre eine Zeile Arbeit — aber
  es ist eine Entscheidung, ob echte Datenstruktur weggebügelt werden soll.
- **Keine Datenbankanbindung.** Kommt in Phase 3 mit `FGaussianDAO` und den
  `FGS_*`-Tabellen.

## Nächster Schritt

Phase 1 wollte Gelände *und* OSM-Aufbauten. Das Gelände steht; für die Aufbauten
fehlt noch die Datenquelle: `OGP` enthält `GEMEINDEN_SH`, `SCHUTZGEBIETE`,
`SEEN`, `WALD` und die zwölf POI-Tabellen, aber keine Gebäudeumringe. Entweder
ein Overpass-Export mit `building=*` für die Kachel, oder erst einmal die
vorhandenen Flächen (`HAFEN`, `STRAND`, `WALD`) als Flächen-Splats auf das
Gelände legen.
