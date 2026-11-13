# Gboard Nintype 2-Finger Swipe: Technische Fehleranalyse & Integrations-Roadmap

Dieses Dokument fasst das vollständige technische Review des 2-Finger-Swipe-Algorithmus (Portierung von Kinetica nach Gboard-patches) zusammen. Es dokumentiert die exakten Ursachen für die Erkennungsprobleme bei gleichzeitigem Daumeneinsatz und die schlechtere Erkennungsrate im Vergleich zum Originalprojekt Kinetica, gefolgt von einem priorisierten, konkreten TODO-Aktionsplan.

---

## 1. Problemstellung & Symptome

1. **Ausfall bei gleichzeitigem Swipe-Start:**
   Wenn zwei Daumen gleichzeitig oder kurz nacheinander (< 40 ms) den Bildschirm berühren und eine Wischbewegung starten, bricht Gboards Gestenverarbeitung oft komplett ab. Es werden entweder keine Trails gezeichnet, nur ein Daumen registriert oder stattdessen ungewollte Einzeltasten (Ghost-Taps) getippt.
2. **Deutlich schlechtere Worterkennung im Vergleich zu Kinetica:**
   Selbst wenn beide Wischspuren visuell gezeichnet werden, ist die Erkennungsgenauigkeit merklich schlechter als in Kinetica. Viele Wörter werden gar nicht oder komplett falsch erkannt, Umlautwörter (ä, ö, ü) schlagen fehl, und die zeitliche Reihenfolge der beiden Daumen führt häufig zu Abbrüchen im Beam Search.

---

## 2. Detaillierte Ursachenanalyse (Root Causes)

Durch Dekompilierung und Smali-Analyse der Gboard-Interna (`AbstractGestureMotionEventHandler`, `BasicMotionEventHandler`, `MotionEventHandlerManager` / `pmm`, `pml`, `tjz`) sowie des Kinetica-Quellcodes konnten fünf fundamentale Kernprobleme identifiziert werden:

### A. Der Dispatcher-Ownership-Konflikt (`BasicMotionEventHandler` lockt `pmm.k`)

In Gboard werden alle Touch-Events über den `MotionEventHandlerManager` (`pmm`) sequenziell an eine Kette aktiver Handler (`pmm.g:[Lqis;`) weitergeleitet:
- Handler `[0]`: `LatinMotionEventHandler` (erbt von `BasicMotionEventHandler`) – zuständig für Einzeltaps, Multi-Touch-Chording und Tastendrücke.
- Handler `[1]`: `LatinGestureMotionEventHandler` (erbt von `AbstractGestureMotionEventHandler`) – zuständig für Swipes und Trails.

#### Was bei gleichzeitigem Touchdown passiert:
1. Wenn Daumen 1 den Bildschirm berührt (`ACTION_DOWN`), setzt `pmm.a` den exklusiven Lock `pmm.k = null`. Beide Handler erhalten das Event.
2. Wenn wenige Millisekunden später Daumen 2 aufsetzt (`ACTION_POINTER_DOWN`), ist dies Action-Code `5` (nicht `0`), wodurch `pmm.k` **nicht** zurückgesetzt wird.
3. Handler `[0]` (`BasicMotionEventHandler`) erhält `ACTION_POINTER_DOWN` **zuerst**.
4. In `BasicMotionEventHandler.t(MotionEvent)` erkennt die interne Chording-Logik (`Lqhw;`) die gleichzeitige Berührung zweier Finger:
   ```smali
   # BasicMotionEventHandler.t(MotionEvent) @ line 0116-011f
   iget-object v14, v1, Lqhw;->q:Lcom/google/android/libraries/inputmethod/widgets/ChordTrackOverlayView;
   if-eqz v14, :cond_chord
   invoke-virtual {v14, p1}, Lcom/google/android/libraries/inputmethod/widgets/ChordTrackOverlayView;->c(Landroid/view/MotionEvent;)V
   :cond_chord
   iget-object v14, v1, Lqhw;->c:Lqit;
   invoke-interface {v14}, Lqit;->m()V   # <-- Fordert exklusiven Lock an!
   ```
5. `Lqit;->m()` wird von der Brücke `Lpml;->m()` implementiert:
   ```smali
   # Lpml.m() @ line 0008-0025
   iget-object v1, v0, Lpmm;->k:Lqis;
   if-nez v1, :cond_locked
   iput-object v4, v0, Lpmm;->k:Lqis;          # pmm.k = BasicMotionEventHandler
   # Iteriere über alle anderen Handler:
   invoke-interface {v2}, Lqis;->gu()Z        # Prüfe gu() auf AbstractGestureMotionEventHandler
   move-result v3
   if-nez v3, :cond_skip_cancel
   invoke-interface {v2}, Lqis;->l()V         # <-- RUFT GESTURE CANCEL/RESET AUF!
   :cond_skip_cancel
   ```
6. **Die Konsequenz:**
   - `pmm.k` gehört jetzt fest dem `BasicMotionEventHandler`.
   - Weil `AbstractGestureMotionEventHandler` standardmäßig `gu() == false` liefert, ruft `pml.m()` sofort `AbstractGestureMotionEventHandler.l()` auf – **dies bricht die Gestenverarbeitung sofort ab und setzt den Zustand zurück!**
   - In allen nachfolgenden `ACTION_MOVE`-Events filtert `pmm.a`:
     ```smali
     # pmm.a(MotionEvent) @ line 0038-0044
     iget-object v2, v3, Lpmm;->k:Lqis;
     if-eqz v2, :cond_deliver
     if-eq v2, v1, :cond_deliver
     invoke-interface {v1}, Lqis;->gu()Z
     move-result v2
     if-nez v2, :cond_deliver
     # SKIP HANDLER v1!
     ```
     Weil `pmm.k != AbstractGestureMotionEventHandler` und `gu() == false`, wird `AbstractGestureMotionEventHandler` für **alle nachfolgenden MOVE-Events komplett übersprungen**. Die Wischbewegung kommt niemals im Gesten-Handler an!

---

### B. Die `this.u` (letterStarted) Überschreibung in `AbstractGestureMotionEventHandler.g()`

Selbst wenn ein Event durchkommt, existiert in Gboards nativem Bytecode eine fatale Überschreibung:

```smali
# AbstractGestureMotionEventHandler.g(MotionEvent) @ line 0110 (:pswitch_2 ACTION_POINTER_DOWN)
invoke-virtual {p1}, Landroid/view/MotionEvent;->getActionIndex()I
move-result v2
invoke-direct {p0, p1, v2}, LabstractGestureMotionEventHandler;->v(Landroid/view/MotionEvent;I)LSoftKeyView;
move-result-object v2
invoke-direct {p0, v2}, LabstractGestureMotionEventHandler;->p(LSoftKeyView;)Z
move-result v2
iput-boolean v2, p0, LabstractGestureMotionEventHandler;->u:Z
```

- `v(event, actionIndex)` ermittelt den `SoftKeyView` unter dem neu aufgesetzten Finger (Pointer 1).
- `p(softKeyView)` prüft, ob es sich um eine druckbare Primärtaste (`f.c() == 1`) handelt.
- Wenn Daumen 2 nur minimal zwischen zwei Tasten, am Rand des Keyboards oder auf einer Sondertaste aufsetzt, gibt `p()` `false` zurück.
- Dadurch wird `this.u` auf `false` gesetzt!
- In `ACTION_MOVE` prüft Gboard:
  ```smali
  0174: iget-boolean v2, p0, LabstractGestureMotionEventHandler;->u:Z
  0178: if-eqz v2, :cond_continue
  017a: return-void    # <-- Bricht sofort ab, ignoriert BEIDE Finger!
  ```
- Obwohl `GboardNintypeRuntime.onGestureEventEntry` am Anfang der Methode `this.u = true` setzt, überschreibt Gboards nativer Code bei Zeile `011e` das Flag unmittelbar danach mit `false`.

---

### C. Falsche Geometrie durch View-Hierarchy-Crawling statt Gboards nativer `Ltjz;`

In `GboardNintypeRuntime.ensureGeometryUpdated()` wurde versucht, Tastenkoordinaten durch rekursives Durchsuchen des Android-View-Trees nach `TextView`-Kindern von `SoftKeyView` zu extrahieren:
1. In modernen Gboard-Themes werden Tastensymbole jedoch häufig direkt im Canvas oder via `Drawable`s gezeichnet – `TextView`s existieren nicht.
2. Dadurch fand `collectLetterKeysFromViewHierarchy` weniger als 20 Tasten (`extracted.size() < 20`) und griff auf den statischen Fallback `NintypeGeometry.createStandardLayout(width, height)` zurück.
3. **Die gravierenden Fehler des statischen Fallbacks:**
   - Er nimmt an, dass die Tasten bei $y=0$ beginnen und 4 gleich große Zeilen bilden (`rowH = height / 4`). In der Realität sitzt über der Tastatur die Vorschlagsleiste (Suggestion Strip) und Header-Padding, wodurch alle berechneten Tastenmittelpunkte $(X, Y)$ um 40 bis 100 Pixel vertikal verschoben sind!
   - Für deutsches Layout (QWERTZ) fehlen die dedizierten Umlauttasten (ä, ö, ü) in der Standardberechnung komplett. Zeile 1 hat in Gboard 11 Tasten, der Fallback rechnet aber mit 9 Tasten – sämtliche Tastenpositionen in Zeile 1 und 2 sind dadurch horizontal massiv deplatziert!
4. **Die native Lösung liegt bereits im Handler:**
   `AbstractGestureMotionEventHandler` besitzt im Feld `g` bereits die vollständige, subpixel-genaue Geometrie `Ltjz;`:
   - `f: [F]` = Exakte X-Mittelpunkte aller Tasten.
   - `g: [F]` = Exakte Y-Mittelpunkte aller Tasten.
   - `b:[I]`, `c:[I]`, `d:[I]`, `e:[I]` = Exakte Bounding-Boxes (Links, Oben, Breite, Höhe).
   - `a: SparseArray<SoftKeyView>` = Alle Tasten-Views, deren `e: SoftKeyDef` die Tastenbeschriftungen (`SoftKeyDef.i`) und Content-Descriptions (`SoftKeyDef.m`) enthält.
   - `h: I`, `i: I` = Modale Tastenbreite und -höhe.

---

### D. Reduzierte Decoder-Pipeline im Vergleich zu Kinetica

In Kinetica (`../kinetica/app/src/main/java/com/kinetica/input/`) besteht die Worterkennung aus einer mehrstufigen, hochgradig fehlertoleranten Architektur (`WordPredictor.kt`, `MergeAlternatives.kt`, `Search.kt`, `ThumbBeam.kt`, `NintypeDecoder.kt`):

1. **Fehlen von `MergeAlternatives` (Timing-Mehrdeutigkeit):**
   Wenn zwei Daumen tippen/wischen, treffen Berührungen oft mit winzigen Zeitabständen (< 120 ms) ein. In Kinetica generiert `MergeAlternatives.sequences()` mehrere alternative chronologische Vertauschungen (Interleavings).
   Im aktuellen Gboard-Patch existiert dies nicht: Die Token-Reihenfolge ist strikt linear nach `startTimeMs`. Wenn Daumen Rechts ("h") 5 ms vor Daumen Links ("t") aufkommt, sucht der Decoder nach Wörtern, die mit "ht..." beginnen, statt "th..." ("the"), und scheitert vollständig.
2. **Reine `ThumbBeam`-Beschränkung ohne Multi-Pass / Rescue:**
   Multi-Finger-Eingaben laufen im aktuellen Gboard-Patch ausschließlich durch `ThumbBeam.java`. Wenn der erste Buchstabe eines Daumens minimal verfehlt wird, schneidet der Beam (Breite 128) den Ast sofort ab. In Kinetica laufen zusätzliche Pässe:
   - Self-Cutting Pass (`cutCandidates`): Automatische Segmentierung langer Wischspuren an Verweilpunkten (Dwells) und scharfen Richtungsumkehrungen (> 110°).
   - Transposition-Pass: Toleranz für Tippfehler durch Vertauschen benachbarter Daumen-Tasten.
   - Dwell-Erkennung in `PointerTrack`: Erkennung von bewussten Pausen auf Buchstaben.
3. **`midlinePx`-Berechnung und der `i % 2`-Hack:**
   Weil die Geometrie im Gboard-Patch oft ungültig war, fielen alle Punkte auf dieselbe Tastaturhälfte (Stream 0). Als Notbehelf wurde in `ThumbBeam.java` (Zeile 67–76) ein statischer `i % 2`-Split eingebaut, der die Token willkürlich abwechselnd auf linken und rechten Daumen verteilt. Dies zerstört die reale physikalische Zuordnung der Hände vollständig.

---

## 3. Priorisierter Implementierungs- und Aktionsplan (TODOs)

### Phase 1: Smali Bytecode Hooks (`GboardNintypeBytecodePatch.kt`)

- [x] **TODO 1.1: `AbstractGestureMotionEventHandler.gu()Z` überschreiben / anhängen**
  - **Ziel:** Verhindern, dass `pmm.a` den Gesten-Handler überspringt und `pml.m()` den Gesten-Handler abbricht (`l()`).
  - **Umsetzung:**
    In `GboardNintypeBytecodePatch.kt` eine neue Methode `gu()Z` zu `AbstractGestureMotionEventHandler` hinzufügen (oder bestehende patchen), die `true` zurückgibt, wenn Nintype aktiviert ist:
    ```smali
    .method public gu()Z
        .registers 2
        invoke-static {}, Ldev/srwi/gboardmultiswipe/extension/nintype/GboardNintypeRuntime;->isNintypeActive()Z
        move-result v0
        return v0
    .end method
    ```
- [x] **TODO 1.2: `BasicMotionEventHandler.t(MotionEvent)` Hook zur Chording-Unterdrückung**
  - **Ziel:** Wenn der Benutzer mit zwei Daumen wischt, darf `BasicMotionEventHandler` (`qhw`) nicht `Lqit;->m()` aufrufen und die Tastatur für Taps sperren.
  - **Umsetzung:** Am Anfang von `BasicMotionEventHandler.t(MotionEvent)` einen Hook injecten: Wenn `GboardNintypeRuntime.shouldSuppressBasicHandler(event)` `true` meldet (z. B. wenn 2 Finger aktiv auf Tastatur wischen), `t()` sofort via `return-void` verlassen.
- [x] **TODO 1.3: `this.u` (letterStarted) Überschreibung in `g(MotionEvent)` patchen**
  - **Ziel:** Sicherstellen, dass `ACTION_POINTER_DOWN` (Zeile `0110` / `011e`) `this.u` nicht auf `false` setzt, wenn bereits ein gültiger Daumen aktiv ist.
  - **Umsetzung:** Den Opcode `iput-boolean v2, p0, ...->u:Z` bei `pswitch_2` so anpassen oder nachlagern, dass `this.u = this.u || v2 || session.sawMultiTouch`.
- [x] **TODO 1.4: Preemption von `pmm.k` absichern**
  - **Ziel:** Wenn Nintype aktiv wird (`nintypeTakeoverActive`), muss `pmm.k` sicher auf `AbstractGestureMotionEventHandler` gesetzt werden, selbst wenn zuvor `BasicMotionEventHandler` eingetragen war.

---

### Phase 2: Geometrie-Engine & Koordinaten-Präzision (`GboardNintypeRuntime.java`)

- [x] **TODO 2.1: Native Geometrie-Extraktion via `this.g` (`Ltjz;`)**
  - **Ziel:** Zuverlässige, themenunabhängige und subpixel-genaue Tastenpositionen ohne View-Tree-Traversal.
  - **Umsetzung:**
    1. Per Reflection das Feld `g` aus `AbstractGestureMotionEventHandler` auslesen (`Ltjz;`).
    2. Die Arrays `f:[F]` (centerX), `g:[F]` (centerY), `d:[I]` (Breite), `e:[I]` (Höhe) und `a:SparseArray<SoftKeyView>` direkt in `NintypeGeometry` übertragen.
    3. Den Tastenzeichen-Wert aus `SoftKeyView.e` (`SoftKeyDef.i:[LCharSequence;` bzw. `SoftKeyDef.m:String`) extrahieren.
    4. Dedizierte Umlaute (ä, ö, ü) im deutschen Layout direkt mit realen Koordinaten erfassen.
- [x] **TODO 2.2: Präzise, dynamische `midlinePx`-Berechnung**
  - **Ziel:** Exakte Trennung zwischen linkem und rechtem Daumenbereich basierend auf den realen Mittelpunkten der Tasten `G`, `H`, `V`, `B`.
  - **Umsetzung:** `midlinePx` anhand des Schwerpunkts zwischen der linken Tastenhälfte (Q-T) und der rechten Tastenhälfte (Y-P) dynamisch berechnen.

---

### Phase 3: Decoder-Pipeline nach Kinetica-Vorbild (`KineticaDtwDecoder.java` & `ThumbBeam.java`)

- [x] **TODO 3.1: Portierung von `MergeAlternatives` (Timing-Interleaving)**
  - **Ziel:** Behebung von Erkennungsfehlern bei fast gleichzeitigem Auftreffen beider Daumen.
  - **Umsetzung:**
    Kinetica's `MergeAlternatives.kt` portieren: Wenn zwei Tokens zeitlich überlappen oder ihr Startzeitpunkt $\le 120\text{ms}$ auseinanderliegt, werden zwei Permutationen generiert ($[T_A, T_B]$ und $[T_B, T_A]$) und beide durch den Decoder bewertet.
- [x] **TODO 3.2: Entfernen des `i % 2`-Split-Hacks in `ThumbBeam.java`**
  - **Ziel:** Reale physikalische Zuordnung basierend auf der stabilen `midlinePx` und Slot-Zuweisung.
  - **Umsetzung:** Den Hack in Zeile 67–76 entfernen und stattdessen `token.slot` (0 = LEFT, 1 = RIGHT) konsistent verwenden.
- [x] **TODO 3.3: Dwell- & Eckenerkennung in `PointerTrack`**
  - **Ziel:** Erkennung bewusster Verweilpunkte und Richtungswechsel bei kontinuierlichen Wischbewegungen.
  - **Umsetzung:**
    In `PointerTrack.addPoint()` Geschwindigkeit und Richtungsänderung tracken. Wenn die Geschwindigkeit unter einen Schwellenwert sinkt oder die Krümmung $> 110^\circ$ beträgt, einen virtuellen Ankerpunkt (Dwell-Token) setzen.
- [x] **TODO 3.4: Multi-Pass & Transposition-Rescue im Decoder**
  - **Ziel:** Wenn `ThumbBeam` für ein schnelles Wischmuster keinen Treffer im Wörterbuch findet, greift ein Fallback-Pass mit leicht gelockerten Geometrie-Schranken (Fuzzy Anchor) und Transpositions-Toleranz.

---

### Phase 4: Integration, Input-Commit & Stabilität

- [x] **TODO 4.1: Ghost-Tap-Unterdrückung bei Gestenende**
  - **Ziel:** Wenn ein 2-Finger-Swipe abgeschlossen wird, dürfen die `ACTION_UP` / `ACTION_POINTER_UP`-Events nicht nachträglich von `BasicMotionEventHandler` als einzelne Tastendrücke interpretiert werden.
  - **Umsetzung:** Sauberes Aufrufen von `BasicMotionEventHandler.l()` (Reset) beim Start eines Nintype-Swipes.
- [x] **TODO 4.2: Kinetica Autospace & Composing-Synchronisation**
  - **Ziel:** Flüssige Wortzusammensetzung mit Live-Vorschau im Textfeld und automatischem Leerzeichen nach 300 ms Inaktivität.
  - **Umsetzung:** Bereits in `GboardNintypeRuntime` angelegt (`finalizeComposingAndAutospace`), muss nach Korrektur von `midlinePx` und Decoder getestet und feinjustiert werden.

---

## 4. Übersicht der betroffenen Quellcode-Dateien

| Komponente | Datei | Relevante Methoden / Änderungen |
| :--- | :--- | :--- |
| **Bytecode Patch** | `patches/.../features/nintype/GboardNintypeBytecodePatch.kt` | `gu()` Hook einbauen, `BasicMotionEventHandler.t()` hooken, `this.u` absichern |
| **Runtime Coordinator** | `extensions/.../nintype/GboardNintypeRuntime.java` | Native `Ltjz;`-Geometrie-Extraktion, `midlinePx`, Dwell-Tracking |
| **Decoder** | `extensions/.../nintype/KineticaDtwDecoder.java` | Integration von `MergeAlternatives`, Multi-Pass-Suche |
| **Dual-Thumb Beam** | `extensions/.../nintype/ThumbBeam.java` | Entfernen des `i % 2`-Hacks, Beam-Width-Optimierung |
| **Geometrie-Modell** | `extensions/.../nintype/NintypeGeometry.java` | Reale Koordinatenübernahme aus `Ltjz;`, Umlaut-Unterstützung |
