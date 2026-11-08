# Doc-os

A very small (~250 KB) document reader and editor for **Android 6.0+**. Opens PDF, Word (.docx),
Excel (.xlsx) and PowerPoint (.pptx) files, lets you **annotate PDFs** and **edit** the Office files.
No ads, no account, **no internet permission, no permissions at all**.

## Features

**PDF** (system PDF engine, so it is fast and tiny)
- Smooth scrolling, pinch / double-tap zoom with sharp re-rendering, fast-scroll handle, page counter and *Go to page*
- Annotate: pen, highlighter, text (any language), eraser, undo, 6 colours, 3 sizes
- Annotations are saved **into the PDF as standard annotations** (visible in Acrobat, Chrome, Preview, ...) and stay editable when you reopen the file in Doc-os
- If a PDF cannot be edited in place (for example encrypted ones), Doc-os offers a flattened copy instead

**Word**: formatting, headings, lists, tables and images are shown; tap a paragraph to edit its text, toggle bold / italic / underline, insert or delete paragraphs.

**Excel**: fast grid with frozen headers, sheet tabs, number / percent / date formatting, formula bar; tap a cell to select, tap again (or double-tap) to edit. Formulas can be typed; they are recalculated when the file is opened in Excel.

**PowerPoint**: slides with shapes, text, pictures and tables; swipe or use the arrows; tap text to edit it.

**Navigation**: recent files with the last position remembered, system back always goes back one step, rotating the phone never loses your work, and you are asked before leaving with unsaved changes. Doc-os also appears in "Open with" for these file types.

## Install

Download the APK from the [releases](../../releases) page and open it. If Google Play Protect shows an
"unknown app" prompt, choose **More details -> Install anyway**: Doc-os requests no sensitive permissions.

## Limits (v1)

- Old binary formats (.doc, .xls, .ppt) are not supported.
- PDF text selection / search and links are not available (Android's PDF engine does not expose them). Annotations made by other apps are shown for common types (ink, highlight, underline, strike-out, boxes, lines, free text) and can be erased.
- Word: headers/footers, footnotes, text boxes and charts are not displayed. Excel: formulas show the cached value (computed by Excel) and are not calculated in the app; charts and images are not displayed. PowerPoint: animations, charts and SmartArt are not displayed; text size inheritance from slide masters is approximate.
- Very large spreadsheets show the first 400,000 cells.

## Privacy

Doc-os never connects to the internet (the permission is not even declared) and stores nothing except your
recent-files list (names, locations, last position) on the device. Working copies of the files you open are
kept in the app's private cache and deleted automatically. Backups are disabled.

## Build

JDK 17 and Gradle 8.7: `gradle assembleRelease` produces an **unsigned** APK (GitHub Actions does this on every push).
Sign it with your own key:

```
zipalign -p -f 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks my.jks --out docos.apk aligned.apk
```

Never commit a keystore (`.gitignore` excludes `*.jks`).

## Design notes

The app is plain Java with no libraries (no Kotlin, no AndroidX), which is why it is so small:
`PdfRenderer` draws pages, a ~600-line PDF reader/writer appends annotations as an incremental update
(works with xref tables and streams, object streams, linearized, rotated and cropped pages), and the Office
formats are read with the platform XML parsers and patched in place so everything you did not touch is preserved.

## License

[MIT](LICENSE)
