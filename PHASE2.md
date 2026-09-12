# FGaussian — Phase 2: Flächen und aufgehende Bauten

Stand: 12.09.2026. Aufbauend auf Phase 1, kompiliert warnungsfrei gegen
`lib/sdoapi.jar`, gegen die echte DGM5-Kachel geprüft.

## Neue Klassen

| Paket | Klasse | Aufgabe |
|---|---|---|
| `core` | `FPolygonFeature` | Flächenobjekt mit Ringen in UTM-Metern, Flächenart, Bauhöhe |
| `shade` | `FLandCover` | Flächenarten mit naturnahen Farben, Zuordnung aus Tabellen- und OSM-Namen |
| `source` | `FCoverGrid` | Flächenartenmaske im Raster des Höhenmodells, Scanline-Füllung |
| `source` | `FSdoPolygonSource` | liest SDO_GEOMETRY aus Oracle, projiziert per `SDO_CS.TRANSFORM` nach 25832 |
| `source` | `FExtrusionBuilder` | macht aus Umringen Wände und Dächer als Splats |
| `demo` | `FGaussianOracleDemo` | Gelände + Oracle-Flächen in einem Fenster |

Erweitert: `FXyzGridSource.buildCloud(…, FCoverGrid)` färbt nach Flächenart statt
nach Höhe, wo eine vorliegt; `FXyzGridSource.heightAt(ost, nord)` liefert die
Geländehöhe bilinear; `FSplatCloud.concat(…)` fügt Gelände und Bauten zusammen.

## Warum die Flächenmaske mehr bringt als eine bessere Höhenrampe

72 % der Kachel liegen auf exakt 0,00 m. Hafenbecken, offene See und nasser
Strand sind höhengleich und trotzdem drei verschiedene Dinge. Keine Rampe der
Welt kann sie trennen — eine Flächenangabe aus den Geodaten schon.

## Oracle-Anbindung

```
FSdoPolygonSource src = new FSdoPolygonSource(connection);
String geom = src.findGeometryColumn("OGP.HAFEN");          // aus den Spatial-Metadaten
List<FPolygonFeature> f = src.load("OGP.HAFEN", geom, null, null,
                                   FLandCover.HARBOUR_BASIN, 0f, window);
```

Drei Dinge, die dabei zu beachten waren:

**SRID.** Die OGP-Bestände liegen in 8307 (Grad), das Höhenmodell in 25832
(Meter). Umprojiziert wird in der Datenbank per `SDO_CS.TRANSFORM`; die
Quell-SRID liest die Klasse selbst aus `ALL_SDO_GEOM_METADATA`. In Metern zu
rechnen ist keine Bequemlichkeit — ein Splat-Radius in Grad wäre eine Funktion
der geografischen Breite.

**Vorfilter.** `SDO_FILTER` auf das Kachelfenster, wobei das Fenster vorher in
die Quell-SRID zurücktransformiert wird. Fehlt der räumliche Index (ORA-13226),
schaltet die Klasse selbsttätig auf vollen Durchlauf mit Prüfung in Java um.

**Elementtypen.** Gelesen werden ETYPE 1003/2003 mit Interpretation 1
(Polygonzug) und 3 (Rechteck aus zwei Punkten). Bögen (1005/2005) werden
gezählt und übersprungen — in OSM-Importen kommen sie nicht vor. Ein
MultiPolygon zerfällt dabei in mehrere Features, was für Rasterung und
Extrusion genau richtig ist.

Die Geometriespalte muss nicht bekannt sein: `findGeometryColumn` holt sie aus
den Metadaten. Tabellennamen werden gegen ein Bezeichnermuster geprüft, bevor
sie ins SQL gehen.

## Extrusion

Umringe werden nicht trianguliert. Der Rasterisierer zeichnet ohnehin nur
Gaußellipsen, also werden Wände und Dächer direkt mit Splats belegt — Wände
spaltenweise entlang jeder Kante und zeilenweise über die Höhe, Dächer über
dieselbe Scanline-Füllung wie die Flächenmaske. Der Abstand ist so gewählt, dass
sich die Splats überlappen; die Wandbeleuchtung nimmt den Betrag des
Skalarprodukts, weil die Ringorientierung der Quelle nicht garantiert ist und
ein Splat keine Rückseite hat.

**Die Höhenskalierung ist ein bewusster Kompromiss.** Das Gelände braucht starke
Überhöhung, sonst ist auf fünf Höhenmetern nichts zu sehen. Bauten mit demselben
Faktor zu strecken wäre konsistent, macht aus einem Hafenschuppen aber einen
Turm. Voreingestellt ist deshalb die Wurzel der Geländeüberhöhung
(bei ×12 also ×3,46); `setHeightScale` stellt es auf jeden anderen Wert.

`FOREST` ist absichtlich *nicht* extrudierbar: ein Waldpolygon als Quader sieht
aus wie ein Quader. Baumbewuchs wäre ein eigener Generator — Stamm plus
Kronensplats an gestreuten Punkten innerhalb der Fläche.

## Messwerte

Zwei Kerne, 1280 × 720, Kachel mit Platzhalterflächen und acht Gebäuden:

- 40 000 Gelände- + 13 552 Bauten-Splats
- Rasterung der Flächenmaske: unter 1 ms
- Bild: 85–104 ms formatfüllend

Zur Einordnung: derselbe Messaufbau ohne Warmlauf meldet 356 ms. Wer die
Bildzeit misst, muss den JIT erst arbeiten lassen — `FSplatSnapshot` macht
deshalb fünf Durchläufe vorweg.

## Was noch fehlt

- **Gebäudedaten.** In `OGP` liegen `GEMEINDEN_SH`, `SCHUTZGEBIETE`, `SEEN`,
  `WALD` und die zwölf POI-Tabellen — keine Gebäudeumringe. Für die Häuser an
  der Strandallee braucht es einen Overpass-Export mit `building=*` für die
  Kachel. Bis dahin zeigt `FGaussianOracleDemo` die vorhandenen Flächen.
- **Höhenspalte.** `Layer` kennt bereits ein `heightColumn`; sobald die
  Gebäudetabelle steht, trägt sie `building:levels × 3 m` oder `height` dort ein.
- **LOD für Bauten.** Der Splat-Abstand ist fest in Metern. Aus der Ferne
  erzeugt das mehr Splats als Pixel; sinnvoll wäre eine Kopplung an die
  Bildauflösung.
- **Kein Datenbankschreiben.** `FGS_SCENE`/`FGS_BLOCK` und `FGaussianDAO`
  kommen in Phase 3.
