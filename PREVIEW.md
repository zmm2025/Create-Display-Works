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

A 3×2 board turned 90° on a bearing keeps its lines of text in order.

| Before | After |
| --- | --- |
| ![Before: TOP repeated on every row](roll90-text-before.png) | ![After: TOP, then LOW, then an empty row](roll90-text-after.png) |

### Trains

Boards on contraptions could lose their discs until the fix.

| Before | After |
| --- | --- |
| ![Before: blank boards on a train](train-before.png) | ![After: media and text on a train](train-after.png) |

![A train carrying two boards](train-moving.gif)

### Claims

Placing a tile next to a claimed board used to join it. It's now refused.

| Before | After |
| --- | --- |
| ![Before: the board grew to three tiles wide](claim-before.png) | ![After: "This board is protected"](claim-after.png) |

### Media Tools setting

| First import | Setting off |
| --- | --- |
| ![The import screen says the first import downloads ffmpeg](import-first-use.png) | ![The import screen says Media Tools are off](import-off-error.png) |
