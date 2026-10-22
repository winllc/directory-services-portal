# WCAG 2.1 AA self-audit: Directory Services Portal web client

| | |
| --- | --- |
| Product | Directory Services Portal (web client), version 0.1.x, `main` after DSP-18/DSP-07/DSP-19 Wave 3 |
| Standard | WCAG 2.1, Levels A and AA (50 success criteria) |
| Date | October 2026 |
| Status | **Self-assessment by the development team.** Not an independent audit; ENS to review. |
| Companion | [Draft VPAT 2.5 (Section 508 edition)](vpat-2.5-draft.md), built from this audit |

## How it was evaluated

1. **Automated, in CI on every push** (`accessibility` job, `web/e2e/a11y.spec.ts`): axe-core 4.13
   through Playwright 1.56 and Chromium, rules tagged `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, on
   23 views and 3 dialogs, each in the light and the dark colour scheme:
   sign-in (with and without an error), home, a directory list and search, an entry (view, edit, new),
   a group, white pages, My profile, metrics, and the administration pages (definitions, a definition,
   forms, a form, schema, permissions, audit log, integrations, data analysis, license); the import,
   new-webhook and bulk-members dialogs; home and My profile as a user without administration.
   **A serious or critical violation fails the build.** The full axe results of each page are kept as
   the `accessibility-report` artifact.
2. **Scripted keyboard checks** (same job): the skip link is the first tab stop and moves focus to the
   main content; the page title names the page; focus moves into a dialog, Escape closes it and focus
   returns to the control that opened it.
3. **Code review** of the shared components (`web/src/components/ui.tsx`: buttons, form rows, modal,
   tabs, toggle, toasts; `FieldInput`; the chart components) against each criterion below.

Not done yet (to schedule before the VPAT is published): screen-reader testing (JAWS and NVDA on
Windows, VoiceOver on macOS), manual zoom and reflow checks at 200% and 400% (320 CSS px), Windows
high-contrast mode, and testing with users with disabilities.

### What the automated checks found, and what was fixed

| Finding | Criterion | Fix |
| --- | --- | --- |
| Muted text (#6b7289) 4.2–4.4:1 on the page, card and tab backgrounds | 1.4.3 | Muted text darkened to #5b6178 (≥ 4.9:1 on every surface); dark scheme #9ca3bd |
| Sidebar secondary text and the "RO" pill 2.9–3.8:1 | 1.4.3 | #a4abc6 (≥ 4.7:1) |
| Links inside running text distinguishable only by colour | 1.4.1 | Underlined in paragraphs, hints and alerts |
| The schema list scrolls but can't be reached by keyboard | 2.1.1 | Focusable, labelled region with a focus ring |
| `aria-label` on chart marks and on a `<code>` element without a role | 4.1.2 (axe "needs review") | Chart marks `role="img"`; the installation id is a labelled group |
| An empty table (headers, no rows) beside the empty-state message | 1.3.1 (axe "needs review") | Table shown only when there are rows |

Found by this review and fixed: no way to bypass the navigation (2.4.1, a skip link added), one page
title for every page (2.4.2, each page now named), form-field and switch borders 1.5:1 against the
page (1.4.11, now ≥ 3.6:1 light and ≥ 3.2:1 dark).

After the fixes, axe reports **no violations of any impact** on the pages above, in either colour
scheme. It lists about 40 elements per run as "needs review" for contrast because their background
is an image (the chevron of `<select>` fields), a gradient (the license banner) or another element
(chart labels over bars); their text colours are the same tokens as checked elsewhere, but they have
not been measured by hand.

## Results

Supports / Partially supports / Does not support / Not applicable, as in the VPAT.

### Perceivable

| SC | Level | Result | Notes |
| --- | --- | --- | --- |
| 1.1.1 Non-text Content | A | Supports | Decorative icons are `aria-hidden`; icon-only buttons have an accessible name. Charts have a text label per mark and keyboard read-out; dashboards also show the numbers as text. |
| 1.2.1 Audio-only and Video-only (Prerecorded) | A | Not applicable | No audio or video. |
| 1.2.2 Captions (Prerecorded) | A | Not applicable | |
| 1.2.3 Audio Description or Media Alternative (Prerecorded) | A | Not applicable | |
| 1.2.4 Captions (Live) | AA | Not applicable | |
| 1.2.5 Audio Description (Prerecorded) | AA | Not applicable | |
| 1.3.1 Info and Relationships | A | Partially supports | Headings, landmarks (`nav`, `main`), labelled fields, `fieldset`/`legend` for groups, table headers and `aria-sort`. Field error messages are shown next to the field and the field is `aria-invalid`, but the message is not yet tied to it with `aria-describedby`. |
| 1.3.2 Meaningful Sequence | A | Supports | DOM order matches the visual order. |
| 1.3.3 Sensory Characteristics | A | Supports | Instructions don't rely on shape, position or sound. |
| 1.3.4 Orientation | AA | Supports | No orientation lock; the layout is responsive. |
| 1.3.5 Identify Input Purpose | AA | Partially supports | Sign-in fields have `autocomplete` (username, current-password). Directory forms are administrator-defined over custom schemas, so My profile fields about the user (e-mail, telephone) don't carry `autocomplete` tokens yet. |
| 1.4.1 Use of Color | A | Supports | Status badges carry text; links in text are underlined. |
| 1.4.2 Audio Control | A | Not applicable | No audio. |
| 1.4.3 Contrast (Minimum) | AA | Supports | Checked by axe in both colour schemes (see the "needs review" note above). |
| 1.4.4 Resize Text | AA | Supports | Relative layout; no text in images. Not yet verified by hand at 200%. |
| 1.4.5 Images of Text | AA | Supports | None used. |
| 1.4.10 Reflow | AA | Partially supports | The navigation collapses and forms stack at narrow widths; wide data tables scroll horizontally (allowed for tables). Not yet verified by hand at 320 CSS px; the form builder and metrics editor are dense. |
| 1.4.11 Non-text Contrast | AA | Supports | Field and switch borders ≥ 3:1; focus ring on every control. Chart series colours are distinguished by labels as well as colour. |
| 1.4.12 Text Spacing | AA | Supports | No fixed-height text containers that clip; not yet verified with a text-spacing bookmarklet. |
| 1.4.13 Content on Hover or Focus | AA | Partially supports | Chart tooltips appear on hover and focus and hide on pointer leave or blur; they can't be dismissed with Escape without moving focus. Native `title` tooltips are used for some details. |

### Operable

| SC | Level | Result | Notes |
| --- | --- | --- | --- |
| 2.1.1 Keyboard | A | Supports | Every control is a native button, link or input, or a focusable element with key handling (charts, the schema list). |
| 2.1.2 No Keyboard Trap | A | Supports | Dialogs close with Escape; no component holds focus. |
| 2.1.4 Character Key Shortcuts | A | Not applicable | No single-key shortcuts. |
| 2.2.1 Timing Adjustable | A | Partially supports | Session idle and absolute timeouts are security controls set by the administrator (ENS_SESSION_*); any user activity extends the idle timeout, and an expired session explains itself at the next sign-in, but there is no warning before it ends. |
| 2.2.2 Pause, Stop, Hide | A | Supports | No auto-updating content besides progress of jobs the user started; animations respect `prefers-reduced-motion`. |
| 2.3.1 Three Flashes or Below Threshold | A | Supports | Nothing flashes. |
| 2.4.1 Bypass Blocks | A | Supports | Skip link to the main content, and landmarks. |
| 2.4.2 Page Titled | A | Supports | Each page sets "&lt;page&gt; · Directory Services Portal". |
| 2.4.3 Focus Order | A | Partially supports | Focus moves into a dialog when it opens and back when it closes, but Tab can leave an open dialog for the page behind it (no focus trap). |
| 2.4.4 Link Purpose (In Context) | A | Supports | |
| 2.4.5 Multiple Ways | AA | Supports | Navigation, search in every directory, and links between entries. |
| 2.4.6 Headings and Labels | AA | Supports | |
| 2.4.7 Focus Visible | AA | Supports | A focus ring on every control (`:focus-visible`), including chart marks and scroll regions. |
| 2.5.1 Pointer Gestures | A | Supports | No path-based or multipoint gestures. |
| 2.5.2 Pointer Cancellation | A | Supports | Actions run on click (up event). |
| 2.5.3 Label in Name | A | Supports | Visible labels are the accessible names. |
| 2.5.4 Motion Actuation | A | Not applicable | |

### Understandable

| SC | Level | Result | Notes |
| --- | --- | --- | --- |
| 3.1.1 Language of Page | A | Supports | `lang="en"`. |
| 3.1.2 Language of Parts | AA | Supports | Directory data is shown as entered; no known passages in another language in the interface. |
| 3.2.1 On Focus | A | Supports | |
| 3.2.2 On Input | A | Supports | Changing a field never submits a form; the directory search only filters the list below the box. |
| 3.2.3 Consistent Navigation | AA | Supports | One navigation on every page. |
| 3.2.4 Consistent Identification | AA | Supports | Shared components. |
| 3.3.1 Error Identification | A | Supports | Errors are described in text at the field and in a summary; fields are `aria-invalid`. |
| 3.3.2 Labels or Instructions | A | Supports | Every field has a label; formats and limits are given as hints. |
| 3.3.3 Error Suggestion | AA | Supports | Messages say what is expected (format, allowed values, range). |
| 3.3.4 Error Prevention (Legal, Financial, Data) | AA | Supports | Deletes and revocations ask for confirmation; imports are checked (dry run) before they write; changes are audited and reversible by an administrator. |

### Robust

| SC | Level | Result | Notes |
| --- | --- | --- | --- |
| 4.1.1 Parsing | A | Supports | Obsolete in WCAG 2.2; React renders well-formed markup with unique ids. |
| 4.1.2 Name, Role, Value | A | Supports | Native controls; custom ones (tabs, switch, dialog, charts) carry roles and states. |
| 4.1.3 Status Messages | AA | Supports | Toasts, job progress and the import summary are live regions (`aria-live`, `role="status"`). |

## Follow-up (Wave 4)

- Tie field error messages to their fields with `aria-describedby` (1.3.1).
- `autocomplete` tokens for profile fields that map to the user's own data (1.3.5).
- A warning before the session idle timeout, with a way to extend it (2.2.1).
- A focus trap in dialogs (2.4.3); Escape to dismiss chart tooltips (1.4.13).
- Manual checks: screen readers, zoom and reflow at 320 CSS px, Windows high contrast; then publish
  the VPAT.
