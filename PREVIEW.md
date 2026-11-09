A board built in chunks that just loaded, such as the `board` fixture right after joining, on Flywheel's `indirect` backend:

| Before | After |
| --- | --- |
| ![Before: every disc black](before-fresh-chunk-board-black.png) | ![After: the stripes show](after-indirect-fresh-chunk-board.png) |

The same check on the other backends after the fix:

| `flywheel:instancing` | Flywheel off |
| --- | --- |
| ![Instancing](after-instancing-fresh-chunk-board.png) | ![Flywheel off](after-flywheel-off-fresh-chunk-board.png) |

The cogwheel next to the board is Create's own `create:cogwheel`. It can still draw black in fresh chunks, because Flywheel leaves Create's visuals unlit in the same way.
