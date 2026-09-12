# FGaussian — Phase 4: Palette-Komponente und Szenenbrowser

Stand: 12.09.2026. Damit ist das gesteckte Ziel erreicht: eine Bibliothek mit
Demo-Frames, palettefähig, mit Oracle-Anbindung.

## Neu

| Paket | Klasse | Aufgabe |
|---|---|---|
| `ui` | `FGaussianViewBeanInfo` | Palette-Beschreibung mit gemaltem Icon und vier beschrifteten Eigenschaften |
| `ui` | `FSceneBrowser` | Liste der gespeicherten Szenen links, Ansicht rechts, Laden im Hintergrund |
| `demo` | `FSceneBrowserDemo` | Fenster um den Browser |

## In die NetBeans-Palette

```
ant jar
```

Dann in NetBeans: **Tools → Palette → Swing/AWT Components → Add from JAR**,
`dist/FGaussian.jar` wählen, `FGaussianView` in eine Kategorie legen. Die
Komponente meldet sich mit Icon, Beschreibung und diesen Eigenschaften im
Eigenschaftenfenster:

- **Renderauflösung** (`renderScale`) — Anteil der Komponentengröße, in dem
  gerendert wird
- **Auflösung beim Ziehen** (`interactiveScale`) — gröbere Stufe während der
  Mausbewegung
- **Eigendrehung** (`spinning`) und **Drehgeschwindigkeit** (`spinSpeed`)

Wolke, Kamera und Renderer sind bewusst *keine* Palette-Eigenschaften — sie
werden im Code gesetzt, nicht im Formulareditor.

## Das Icon ist gemalt, nicht geladen

Drei überlappende Gaußellipsen, genau die Form, die der Rasterisierer zeichnet.
Ein gemaltes Icon kann beim Verpacken nicht verlorengehen und bedient jede
Größe, die NetBeans anfragt.

**Dabei ist mir ein Fehler unterlaufen, der hier festgehalten sei**, weil er bei
jedem gemalten Icon wieder auftreten kann: Ein `RadialGradientPaint` wird von der
aktuellen Transformation des `Graphics2D` mitverzerrt. Wird er *vor* dem
`translate` gesetzt und die Form danach verschoben, wandert der Mittelpunkt des
Verlaufs mit — im Bild bleibt nur der ausgefranste Rand der Glocke, bei 16 px
praktisch nichts. Richtig ist: erst `translate`/`rotate`/`scale`, dann den
Verlauf mit Mittelpunkt (0,0) setzen, dann füllen. Aufgefallen ist es nur, weil
ich das Icon gerendert und angeschaut habe, statt es für fertig zu halten.

## Szenenbrowser

`FSceneBrowser` lädt in einem `SwingWorker`. Bei 40 000 Splats sind es 66 ms, bei
sechsstelligen Zahlen wird daraus eine spürbare Pause — und die gehört nicht in
den EDT. Während des Ladens bleibt die alte Szene stehen, nur die Statuszeile
wechselt. Sie zeigt danach Splatzahl, Ladezeit, Überhöhung und den
UTM-Szenenursprung.

```
java -cp build\classes;lib\ojdbc11.jar com.dan.fgaussian.demo.FSceneBrowserDemo
```

## Stand des Pakets

```
com.dan.fgaussian
├── core    FSplatCloud, FSplatCodec, FQuat, FPolygonFeature
├── render  FSplatRenderer, FSplatSorter, FCamera
├── shade   FHeightRamp, FLandCover
├── source  FXyzGridSource, FCoverGrid, FSdoPolygonSource, FExtrusionBuilder
├── db      FGaussianDAO
└── ui      FGaussianView, FGaussianViewBeanInfo, FOrbitController, FSceneBrowser
```

Gemessen auf deinem Rechner: Import der Kachel 501 ms, Laden 66 ms.

## Was offen bleibt

- **Gebäude.** Es fehlt weiterhin ein Overpass-Export mit `building=*` für die
  Kachel. `FExtrusionBuilder` und `Layer.heightColumn` warten darauf.
- **Spherical Harmonics.** Gespeichert und geladen werden sie bereits, ausgewertet
  noch nicht — das fehlt für den PLY-Weg (`FSHEvaluator`).
- **PLY-Import.** Der Parser für 3DGS-Rekonstruktionen; das Datenmodell steht.
- **LOD.** Splat-Abstand bei Bauten ist fest in Metern; aus der Ferne entstehen
  mehr Splats als Pixel.
- **Anker.** `FGS_ANCHOR` ist angelegt, aber von keiner Klasse bespielt.
