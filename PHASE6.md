# FGaussian — Phase 6: Flächenarten aus dem Gelände

Stand: 12.09.2026.

## Warum nicht aus OSM

Der Overpass-Export für Wasser und Hafenanlagen lieferte für den Ausschnitt
genau **zwei** Objekte: ein Polygon der Hemmelsdorfer Au und die Küstenlinie —
letztere als `LineString` über die gesamte Lübecker Bucht, 1331 Stützpunkte,
wovon ein Bruchteil in der Kachel liegt. Linien liest `FSdoPolygonSource` nicht
(nur ETYPE 1003/2003), und eine Küstenlinie ist ohnehin erst dann eine Fläche,
wenn man sie über den Kachelrand schließt.

## Wasser ist, was eben ist — nicht, was auf null liegt

Der erste Anlauf setzte eine Höhenschwelle bei 0,05 m. Das Ergebnis sah im
Renderbild falsch aus, und die Messung zeigte warum:

| | Höhe im DGM | Zellen |
|---|---|---|
| offene See | 0,00 m | 28 840 |
| **Hafenbecken** | **0,68 m** | **758** (310 × 110 m) |

Das Becken liegt im Geländemodell **nicht** auf null. Die Schwelle hat es als
flaches Land gewertet, und weil es ufernah liegt, wurde daraus Strand — im Bild
eine sandfarbene Fläche mitten im Hafen.

Die Höhe trennt Wasser von Land also nicht. Die **Form** tut es: Eine
Wasserfläche ist eben, gleich auf welchem Niveau.

## Das Verfahren in `FTerrainCover`

1. **Ebenheit** je Zelle: Höhenspannweite im 3×3-Fenster unter 0,10 m.
2. **Zusammenhangskomponenten** der ebenen Zellen.
3. Eine Komponente ist Wasser, wenn sie größer als 2500 m² ist *und* ihre Höhen
   insgesamt innerhalb von 0,30 m liegen. Ein ebenes Feld fällt durch die zweite
   Bedingung, ein Parkplatz durch die erste.
4. **Wachsen**: Der ebene Kern ist nur der Innenbereich — zum Ufer hin wird das
   Raster unruhig. Von dort aus wird über die Höhe weitergewachsen (±0,08 m um
   den Spiegel). Für Niendorf: aus 363 Kernzellen werden genau die 758, die auch
   eine unabhängige Auszählung des Höhenbands 0,60–0,75 m ergibt.
5. **Randkontakt** entscheidet: Wasser am Kachelrand ist offene See, alles andere
   geschütztes Wasser. Hier trennt die Höhendifferenz ohnehin schon.
6. **Strand**: Land dicht am Wasser, höchstens 1,2 m über dem Seespiegel.

Ergebnis für die Kachel: 28 995 Zellen See, **758 Hafenbecken**, 700 Strand,
gefundene Spiegel 0,00 m und 0,673 m. Laufzeit 35 bis 39 ms.

## Zwei Fallen, die dabei zuschnappten

**Der Kachelrand.** Die Ebenheitsprüfung lehnte Randzellen ab, weil dort
Nachbarn fehlen. Dadurch war die äußerste Zellreihe nie eben, die Wasserfläche
berührte den Rand nicht mehr — und die ganze Ostsee wurde als geschütztes
Wasser eingestuft. Jetzt werden fehlende Nachbarn übergangen statt die Zelle
abgelehnt.

**Die Toleranz.** Bei 0,06 m fiel das Hafenbecken durch: Die DGM-Werte dort
streuen zwischen 0,65 und 0,70 m, im 3×3-Fenster also bis zu 0,08 m. Mit 0,10 m
greift es.

## Was das nicht kann

Liegen zwei Gewässer auf demselben Spiegel und hängen zusammen, trennt dieses
Verfahren sie nicht — dafür bräuchte es echte Hafendaten. Für Niendorf
entscheidet die Höhendifferenz von 68 cm die Sache von allein.
