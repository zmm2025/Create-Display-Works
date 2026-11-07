### Display Link text

Display Link text from Nixie tubes (top to bottom: 3×2 board, 1×1, progress-bar symbols, `§` codes and a tab, a line break, numbers with `°`, a translated component, styled text, emoji and CJK, accents, long text).

| Before | After |
| --- | --- |
| ![Before: accents, symbols, emoji and line breaks show as question marks](before-text.png) | ![After: accents fold to ASCII, symbols get glyphs, line breaks continue on the next row](after-text.png) |

Block characters from Create's progress bars, plus the check mark, arrow and square from the factory gauge source:

![Filled and empty progress-bar cells, then a check mark, an up arrow and a small square](after-bars.png)

Real sources: Create's kinetic speed, content observer item list, and stockpile switch fill level as a percentage and a progress bar:

![48 RPM, a five-line item list, 51% and a half-filled progress bar](after-sources.png)

### Boiler gauges

Empty bar slots used to differ only by color, which a board can't show. They now draw as a dot on the baseline.

| Before | After |
| --- | --- |
| ![Before: every gauge shows five identical bars](boiler-before.png) | ![After: Heat shows one bar and four empty slots](boiler-after.png) |

### Quarter turns

A board turned 90° on a bearing now clears, like Create's display boards.

| Our board, before the turn | Our board, after | Create's board, after |
| --- | --- | --- |
| ![TOP and LOW on a 3×2 board](ours-3x2-90-start.png) | ![The same tiles, now 2×3 and blank](ours-3x2-90-end.png) | ![Create's board, also blank](create-3x2-90-end.png) |

### Trains

Boards on contraptions could lose their discs until the fix.

| Before | After |
| --- | --- |
| ![Before: blank boards on a train](train-before.png) | ![After: media and text on a train](train-after.png) |

![A train carrying two boards](train-moving.gif)

### Claims

Claim mods can't tell the client that a board is protected, so the placement ghost still shows, and the click is refused.

| Aiming at a claimed board | Clicking the board | Clicking the ground beside it |
| --- | --- | --- |
| ![Ghost tile and arrow](claim-hover.png) | ![The claim mod refuses the click](claim-refused.png) | !["This board is protected"](claim-ground-refused.png) |

### Media Tools

The first import that needs tools now asks first, instead of a note on the empty screen. Turning Media Tools off still gives an error when importing.

| Before | After | Setting off |
| --- | --- | --- |
| ![The note on the empty screen](import-first-use.png) | ![The Download media tools popup](popup.png) | ![Media Tools are off](import-off-error.png) |

### Schematicannon

Printed boards match Create's: same blocks and cost, blank content. The tile borders now match the source board.

| Source | Printed | Written to after printing |
| --- | --- | --- |
| ![Our boards and Create's with text and a clip](sch-source.png) | ![Both printed blank](sch-result.png) | ![Printed boards showing new text](sch-printed-used.png) |
