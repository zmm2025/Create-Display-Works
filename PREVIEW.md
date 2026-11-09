The Pixel Matrix Display is a new block, so there's no "before" to compare with.

| Colored text, a Display Link on line 3 | A 16 by 16 wall showing one picture |
| --- | --- |
| ![Colored text and a Display Link value on a 6 by 4 board](matrix-text-and-link.png) | ![A sunset across a 16 by 16 wall](pixel-matrix-wall.png) |

| Importing a video | The video playing |
| --- | --- |
| ![The media screen with a video loaded](pixel-matrix-import-screen.png) | ![A video playing on the board](pixel-matrix-import.gif) |

| Dye recolors plain text | A clip too large for the server is cut short |
| --- | --- |
| ![Plain text turned light blue by dye](matrix-text-dyed.png) | ![The media screen saying it saved the first 10 seconds](matrix-clip-shortened.png) |

| Losing rotation fades the screen out | Without rotation |
| --- | --- |
| ![The screen going dark when rotation stops](matrix-power-off.gif) | ![A dark board seen from the side](pixel-matrix-unpowered-angle.png) |

| Ponder: text | Ponder: media |
| --- | --- |
| ![The Ponder scene about text](matrix-ponder-text-2.png) | ![The Ponder scene about media](matrix-ponder-media-3.png) |

Frame times on a 16 by 16 wall (256 blocks), Flywheel instancing, measured back to back in one session:

| View | Flip-Dot Display | Pixel Matrix Display |
| --- | --- | --- |
| Nothing in view | 170 FPS | 124 FPS |
| Wall, holding still | 103 FPS | 122 FPS |
| Wall, playing a clip | 52 to 80 FPS | 99 to 148 FPS |
