# FGaussian — Phase 5: Rekonstruktionen, Anker, Gebäudedaten

Stand: 12.09.2026. Drei der vier offenen Punkte sind erledigt, der vierte
vorbereitet.

## Neu

| Ort | Datei | Aufgabe |
|---|---|---|
| `source` | `FPlySplatSource` | liest 3DGS-PLY (binär und ASCII), Grad 0 bis 3 |
| `shade` | `FSHEvaluator` | wertet Spherical Harmonics zur blickrichtungsabhängigen Farbe aus |
| `sql/` | `ogp_gebaeude.sql` | Overpass-Abfrage, Tabelle `OGP.GEBAEUDE`, Grants, Prüfabfragen |

Erweitert: `FSplatRenderer` wertet SH je Bild aus (`evaluateSh`, abschaltbar) und
gleicht die Deckkraft gegen den Tiefpasszuschlag aus; `FGaussianDAO` schreibt und
liest Anker; `FSceneTool ply <datei>` importiert eine Rekonstruktion;
`FGaussianOracleDemo` kennt die Gebäudeebene.

## Variante B ist damit real

Das Konzept hatte versprochen: wenn `FSplat` von Anfang an Quaternion, drei
Skalen und einen SH-Block trägt, kostet der PLY-Import nur noch einen Parser.
Das hat gehalten — am Renderer wurde für den PLY-Weg nichts geändert außer der
SH-Auswertung, die ohnehin vorgesehen war.

Drei Fallen beim Format, alle gegen eine Prüfdatei mit bekannten Werten
kontrolliert:

**Die Attribute sind transformiert gespeichert.** `scale_i` ist logarithmiert
(also `exp`), `opacity` ist logit-transformiert (also Sigmoid). Wer das
übersieht, bekommt mikroskopische Splats bei voller Deckkraft.

**Die Quaternionreihenfolge ist eine andere.** In der Datei steht `rot_0..3` als
w, x, y, z — im übrigen Paket gilt x, y, z, w.

**Die Achsen brauchen eine Drehung, keine Spiegelung.** Rekonstruktionen liegen
in der COLMAP-Konvention mit y nach unten. Naheliegend wäre, y und z einfach zu
negieren — aber eine Spiegelung kehrt die Händigkeit um und damit den Drehsinn
sämtlicher Quaternionen; die Splats stünden falsch verdreht. Richtig ist eine
Drehung um 180 Grad um die x-Achse, angewandt auf Positionen *und* Quaternionen.

**Und das f_rest-Layout ist kanalweise:** erst alle höheren Koeffizienten von
Rot, dann Grün, dann Blau — nicht koeffizientenweise, wie man es beim Lesen des
Speicherlayouts von `FSplatCloud` vermuten würde.

### Prüfung

Eine synthetische Kugel aus 20 000 Splats mit SH-Grad 1, aus drei Blickwinkeln
gerendert, wechselt die Farbe von Blau über einen Verlauf nach Orange; mit
`evaluateSh = false` bleibt eine einzige Farbe aus dem Koeffizienten 0. Genau
das ist der Effekt, für den der SH-Block existiert.

```
java com.dan.fgaussian.demo.FSceneTool ply rekonstruktion.ply hafen3dgs
java com.dan.fgaussian.demo.FSceneTool show hafen3dgs
```

## Deckkraftausgleich statt LOD

Zum Punkt „LOD für die Bauten" nur die halbe Wahrheit: Ein hierarchisches LOD
wäre ein eigener Bau und ist **nicht** gemacht. Erledigt ist der Teil des
Problems, der sichtbar störte.

Die 2D-Kovarianz bekommt einen Tiefpasszuschlag von 0,3 Pixel², damit kleine
Splats nicht flimmern. Der vergrößert sie aber auch — bleibt die Deckkraft
gleich, deckt ein ferner Splat mehr Fläche ab, als ihm zusteht, und entfernte
Bauten laufen zu einem Block zusammen. Der Ausgleich ist das Verhältnis der
Determinanten vor und nach dem Zuschlag: `alpha *= sqrt(detRoh / detDilatiert)`.
Eine Zeile, und ferne Geometrie bleibt luftig.

Was das *nicht* löst: Der Splat-Abstand an Wänden und Dächern ist fest in
Metern, also entstehen aus der Ferne weiterhin mehr Splats als Pixel. Die
Zeitersparnis müsste aus einer Hierarchie kommen.

## Anker

`FGaussianDAO.saveAnchors` / `loadAnchors` bespielen `FGS_ANCHOR`. Punkte gehen
als `SDO_GEOMETRY`-Ausdruck hinein und kommen über `GEOM.SDO_POINT.X/Y` wieder
heraus — für Punktgeometrien braucht es damit weder JGeometry noch einen
oracle-eigenen Typ im Java-Code.

Dabei eine Stolperstelle, die still falsch läuft: `ResultSet.wasNull()` bezieht
sich immer auf den *zuletzt gelesenen* Wert. Die Prüfung muss also unmittelbar
nach dem `getLong` der Palette stehen, nicht am Ende der Zeile — sonst prüft man
die Koordinate und bekommt bei jedem Anker eine Palette-ID von 0.

## Gebäude: dein Zug

`sql/ogp_gebaeude.sql` enthält alles, was dafür fehlt:

1. die fertige Overpass-Abfrage für den Kachelausschnitt
   (`53.9905,10.7985,54.0055,10.8230`),
2. `CREATE TABLE OGP.GEBAEUDE` mit `LEVELS`, `HEIGHT` und der virtuellen Spalte
   `HEIGHT_M` (Höhe, ersatzweise Geschosse × 3 m),
3. Metadaten in SRID 8307, Spatial-Index, Grant an DEMO,
4. Prüfabfragen für die Anzahl im Ausschnitt.

`FGaussianOracleDemo` kennt die Ebene bereits. Solange die Tabelle fehlt, meldet
die Demo „übersprungen (keine Spatial-Metadaten)" und zeigt die übrigen Ebenen —
sobald sie da ist, stehen die Häuser ohne weitere Änderung.

## Damit steht

```
com.dan.fgaussian
├── core    FSplatCloud, FSplatCodec, FQuat, FPolygonFeature
├── render  FSplatRenderer, FSplatSorter, FCamera
├── shade   FHeightRamp, FLandCover, FSHEvaluator
├── source  FXyzGridSource, FCoverGrid, FSdoPolygonSource,
│           FExtrusionBuilder, FPlySplatSource
├── db      FGaussianDAO
└── ui      FGaussianView, FGaussianViewBeanInfo, FOrbitController, FSceneBrowser
```

Offen bleibt allein das echte LOD — und die Gebäudedaten, die nur noch
eingeladen werden müssen.
