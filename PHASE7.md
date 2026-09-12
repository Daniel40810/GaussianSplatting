# FGaussian — Phase 7: Detailstufen, Anker, PLY-Härtetest

Stand: 12.09.2026.

## Neu

| Paket | Klasse | Aufgabe |
|---|---|---|
| `core` | `FCovariance` | Kovarianz aus Skalen und Quaternion, Verschmelzen, Jacobi-Zerlegung zurück |
| `render` | `FSplatHierarchy` | Detailstufen: Ebenen gröberer Ersatzsplats, Auswahl je Bild |

Erweitert: `FSplatRenderer.render(cloud, indices, count, …)` zeichnet eine
Indexliste; `FGaussianView.setHierarchy(…)` mit zwei Fehlerschwellen;
`FSceneBrowser` zeigt die Anker einer Szene und fliegt sie an.

## Verschmelzen ist kein Mitteln

Eine Gruppe von Gaußen durch einen zu ersetzen heißt nicht, die Kovarianzen zu
mitteln. Der Ersatz muss auch die **Streuung der Mittelpunkte** aufnehmen, sonst
wird er zu klein und die Gruppe verschwindet, statt zu einem Fleck zu werden.
Richtig ist der Verschiebungssatz:

```
Sigma = Summe w_i * (Sigma_i + d_i * d_i^T) / Summe w_i
```

Geprüft: zwei Einheitskugeln im Abstand 10 m ergeben eine lange Halbachse von
5,099 — genau √26. Die Zerlegung zurück in Skalen und Quaternion (Jacobi, zwölf
Sweeps) hat über 20 000 Zufallssplats einen größten relativen Fehler von
7,4·10⁻⁷, also float-Genauigkeit.

## Der Denkfehler, der LOD zunächst wirkungslos machte

Erster Anlauf: „nimm den Ersatzsplat, wenn er klein genug am Bildschirm ist".
Ergebnis: 104 864 von 104 872 Splats gewählt, also nichts gespart.

Der Grund ist strukturell: **Ein Ersatzsplat ist immer größer als seine
Kinder** — er deckt ja deren ganze Gruppe ab. Eine Prüfung auf seinen eigenen
Bildradius ist damit strenger als bei den Kindern und trifft nie zuerst zu.

Maßgeblich ist nicht die Größe, sondern der **Lagefehler**: wie weit der
vertretene Teilbaum um den Ersatzmittelpunkt streut. Dieser Fehlerradius wird
beim Aufbau mitgeführt (weitester Kindmittelpunkt plus dessen eigener Fehler)
und in Pixel projiziert. Danach greift die Auswahl.

## Der zweite Fehler: die Auswahl kopierte

Nach dem ersten Fix war LOD in der Nahansicht *langsamer* als ohne — weil
`select` die gewählten Splats in eine eigene Wolke kopierte, also vierzehn
Arrays je Bild. Jetzt liegen alle Ebenen in **einer** Wolke, und die Auswahl ist
eine reine Indexliste, die der Rasterisierer direkt verarbeitet. Damit kostet
LOD im ungünstigsten Fall nichts mehr.

## Was es bringt — und was nicht

Niendorf mit dichten Bauten (Splat-Abstand 0,4 m), 723 945 Splats, Totale,
1400 × 800 auf zwei Kernen:

| Fehlerschwelle | gezeichnete Splats | Zeit | Faktor | Bildabweichung |
|---|---|---|---|---|
| ohne LOD | 723 945 | 546 ms | 1,00 | — |
| 1 px | 722 357 | 553 ms | 1,00 | 0 |
| 2,5 px | 560 963 | 471 ms | 1,17 | — |
| 3 px | 319 068 | 305 ms | 1,75 | 3,0/255 |
| 4 px | 45 181 | 154 ms | **3,51** | 4,7/255 |
| 6 px | 28 117 | 117 ms | 4,71 | 5,8/255 — Raster sichtbar |

**Der wichtigste Befund:** Bei 2,5 px sinkt die Splatzahl um 42 %, die Zeit aber
nur um 16 %. Dieser Rasterisierer ist **pixel- und nicht splatbegrenzt** — ein
Ersatzsplat deckt dieselbe Fläche ab wie die Gruppe, die er vertritt, also
bleibt die Rasterarbeit gleich. Gespart werden Projektion, Sortierung und
Kacheleinträge. Das schlägt erst durch, wenn die Schwelle so weit gelockert
wird, dass ganze Ebenen wegfallen.

Brauchbar ist der Bereich **3 bis 4 px**. Ab 6 px wird die gitterweise
Zusammenfassung als Raster auf der Wasserfläche sichtbar.

Deshalb sitzt LOD in der Ansicht dort, wo es hingehört: als grobe Stufe während
der Mausbewegung (`interactiveLodThreshold`, voreingestellt 4 px) und fein im
Ruhezustand (`lodThreshold`, 1 px) — dasselbe Muster wie bei der Renderauflösung.

Speicher: die Hierarchie kostet 9 % Aufschlag bei 724 000 Splats (64 % bei
105 000, weil dort die groben Ebenen im Verhältnis mehr wiegen). Aufbau 489 ms.

## Anker

`FSceneBrowser` zeigt unter der Szenenliste die Anker der gewählten Szene; ein
Klick setzt den Kamerazielpunkt darauf. Die Umrechnung geht über `CENTRE_EAST`
und `CENTRE_NORTH` der Szene — und die Nordrichtung kehrt sich dabei um, weil
die Szenenachse z nach Süden zeigt.

## PLY-Härtetest

Gegen fünf erzeugte Varianten geprüft, jeweils mit Plausibilitätskontrolle
(Skalen positiv, Deckkraft in 0..1, Quaternionen normiert):

| Datei | Format | Grad | Splats | Zeit |
|---|---|---|---|---|
| little endian | binär | 3 | 2 000 | 68 ms |
| big endian | binär | 2 | 2 000 | 35 ms |
| ASCII | Text | 1 | 500 | 47 ms |
| vertauschte Attributreihenfolge | binär | 2 | 1 000 | 12 ms |
| groß | binär | 3 | **600 000** | 1 253 ms |

Alle gelesen. Was weiterhin aussteht, ist eine echte Rekonstruktion — die
Konventionen (log-Skalen, Logit-Deckkraft, w-x-y-z, kanalweise f_rest,
COLMAP-Achsen) sind gegen bekannte Werte geprüft, aber nicht gegen eine Datei
aus einem echten Trainingslauf.

## Offen

- Ersatzsplats tragen keinen SH-Block: Rekonstruktionen sollten vor dem Aufbau
  ihre Grundfarbe einbacken (`FSHEvaluator.bakeBaseColor`).
- Die Zusammenfassung ist gitterweise. Ein echter Octree mit Nachbarschaft statt
  fester Zellgrenzen würde das Raster bei groben Schwellen mildern.
