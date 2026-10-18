# UI OVERHAUL — PROGRESS LOG

Scope frozen: **HTML + CSS only, no JavaScript**. No Java, SQL, routes,
controllers, form field names or security were touched. Text stays in message
keys; form `th:action` (CSRF), label/id pairs, the skip link, `lang` and
aria-labels are all preserved. Class names `.tile`, `.topbar`, `.main-container`
and `.layout-*` were kept, so `check_css.sh` and `smoke_test.sh` need no change.

Environment note: this machine has **no JDK / Maven / MySQL**, so
`./mvnw -q compile` and `smoke_test.sh` cannot run here and are reported as
**DEFERRED / static-only verification**. `check_css.sh` and `git` do run.

---

## STEP 0 — DIAGNOSE (no edits)

The "random scrollbar in the middle right" had three causes stacked together:

1. `body { height: 100vh; overflow: hidden }` — the document itself could not
   scroll, so the page could never use the real window scrollbar.
2. `.main-container { overflow: auto }` — the only scroller. Because it has
   `padding: 24px`, its scrollbar was drawn **inset** from the window edge
   (hence "middle right"), and the mouse wheel did nothing over the top bar or
   the footer.
3. `.tile__body { overflow: auto }` — every card taller than its fixed height
   got **its own** inner scrollbar; which card that was changed with the data,
   so the extra bar looked random per page (home / forecast / community / lab /
   owner all showed different ones).

Fix: make the **document** the single scroller; let tiles grow with content.

## STEP 1 — ONE DOCUMENT SCROLLBAR (done)

File touched: `src/main/resources/static/css/style.css` only.

- `html`: added `scrollbar-gutter: stable; scrollbar-width: thin;
  scrollbar-color: var(--border-strong) transparent;` so the layout never jumps
  sideways when the bar appears.
- `body`: removed `height: 100vh; overflow: hidden;` → now
  `min-height: 100vh; min-height: 100dvh; overflow: visible;`. The whole page
  scrolls as one column (top bar + tiles + footer).
- `.main-container`: `overflow: auto` → `overflow: visible` (no longer a
  scroll region; the bar moves to the true window edge).
- `.tile__body`: `overflow: auto` → `overflow: visible` (tiles grow with their
  content; nothing traps the wheel).
- Added a themed `::-webkit-scrollbar` set (12px, transparent track, floating
  rounded thumb in `--border-strong`, accent on hover) to match the dark theme.
- Removed the now-redundant `@media (max-width: 800px) { body { ... overflow:
  auto } }` override — the document is the scroller at every width.

Motion (accessibility): every `transition:` and every hover/focus `transform`
was **moved out of its rule** and collected into one new
**section 8 — MOTION** block, wrapped in
`@media (prefers-reduced-motion: no-preference)`. Visitors who ask for less
motion now get a completely still page (no lifts, no sliding arrows), while
colour changes and focus rings still work for everyone.

Pre-existing bug fixed while in the area (CSS comment only): the comment on
`.map-svg .district` contained `.band-*/.crop-*`, whose `*/` closed the comment
early and left a stray `*/` (check_css.sh counted one extra closer). Reworded to
`…a .band-* or .crop-* class`.

### Verification for step 1

- `bash check_css.sh` → CSS checks PASS (braces balanced 230/230, comments
  balanced 124/124, `.tile` / `.topbar` / `.main-container` / all real
  `layout-*` present, every page links the stylesheet).
  The 3 remaining script FAILs are **pre-existing false positives**:
  `.card`, `.layout-master-stack` and `.layout-xxx` appear only inside template
  *comments* / as `.card__btn` / `.lab-card`, never as a real class. Unrelated
  to this step.
- `./mvnw -q compile` → **DEFERRED** (no JDK/Maven on this machine).
- `bash smoke_test.sh --server.port 809x` → **DEFERRED** (no MySQL/JDK here).
- Headless screenshots → **DEFERRED** (Chrome exists, but the app needs a JDK +
  MySQL to serve the pages; cannot start a server on this machine).

### Deferred / needs Java (out of scope, listed for the end)

- Nothing was found that *requires* a Java change for the scroll fix. The one
  verification gap is running the app to confirm visually.

## NEXT STEPS (planned, not started)

- Focus visibility on every control; 44px tap-target audit.
- WCAG AA contrast pass on `--text-faint`, `.btn--ghost`, status colours.
- Spacing / typography rhythm; tile & card refinement.
- Review `.map-svg { max-height: 70vh }` and `.layout-home > .tile
  { min-height: 290px }` against the "tiles grow with content" goal.
