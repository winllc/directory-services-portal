# Directory Services Portal Accessibility Conformance Report — Section 508 Edition

> **DRAFT — SELF-ASSESSMENT FOR ENS REVIEW. NOT FOR PUBLICATION.** Prepared by the development
> team from the [WCAG 2.1 AA self-audit](wcag-2.1-aa-self-audit.md). It has not been independently
> verified, and the manual checks listed under "Evaluation methods" are still to be done. ENS must
> review it, complete the contact and support sections, and approve it before it is given to a
> customer.

(Based on VPAT® Version 2.5Rev, Section 508 edition. "VPAT" is a registered service mark of the
Information Technology Industry Council (ITI).)

## Name of product and version

Directory Services Portal, version 0.1.x (web client and API), Wave 3 build.

## Report date

October 2026 (draft).

## Product description

A web application for managing LDAP directories with custom schemas: administrator-defined forms and
directories, entry search, viewing and editing, self-service profiles, white pages, metrics and data
analysis, an audit log, and an integration API. Used in a desktop or mobile web browser.

## Contact information

ENS Solutions — *to be completed by ENS (accessibility contact).*

## Notes

- Scope: the web client served by the portal's web tier, as used by end users and administrators.
  The integration API (`/api/v1`) has no user interface and is out of scope.
- Forms and directories are configured by each customer's administrators; the portal renders every
  form field with a visible label and its hints, so forms built with it inherit the results below.
- The client follows the operating system's light or dark colour scheme; both were evaluated.

## Evaluation methods used

- Automated testing with axe-core 4.13 (Playwright 1.56, Chromium) against WCAG 2.0 and 2.1 Levels A
  and AA on 23 pages and 3 dialogs, in light and dark colour schemes, run in continuous integration on
  every change; any serious or critical violation fails the build.
- Scripted keyboard tests: skip link, page titles, focus into and out of dialogs.
- Code review of shared user-interface components against each success criterion.
- **Not yet performed** (required before publication): screen-reader testing (JAWS, NVDA, VoiceOver),
  manual magnification and reflow testing (200% and 400%), Windows high-contrast mode, and testing
  with users with disabilities.

## Applicable standards/guidelines

| Standard/Guideline | Included in report |
| --- | --- |
| Web Content Accessibility Guidelines 2.0 | Level A (Yes), Level AA (Yes), Level AAA (No) |
| Web Content Accessibility Guidelines 2.1 | Level A (Yes), Level AA (Yes), Level AAA (No) |
| Revised Section 508 standards published January 18, 2017 and corrected January 22, 2018 | Yes |

## Terms

- **Supports**: the functionality of the product has at least one method that meets the criterion
  without known defects or meets with equivalent facilitation.
- **Partially Supports**: some functionality of the product does not meet the criterion.
- **Does Not Support**: the majority of product functionality does not meet the criterion.
- **Not Applicable**: the criterion is not relevant to the product.
- **Not Evaluated**: the product has not been evaluated against the criterion (AAA only).

## WCAG 2.x Report

Section 508 Chapter 5 (501.1) and Chapter 6 (602.3) require that non-web software and support
documentation conform to WCAG 2.0 Level A and AA. The 2.1 criteria are included for customers who
ask for them.

### Table 1: Success Criteria, Level A

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 1.1.1 Non-text Content (Level A) | Supports | Icons are hidden from assistive technology or named; charts have per-value text labels and keyboard read-out. |
| 1.2.1 Audio-only and Video-only (Prerecorded) (Level A) | Not Applicable | No audio or video content. |
| 1.2.2 Captions (Prerecorded) (Level A) | Not Applicable | No audio or video content. |
| 1.2.3 Audio Description or Media Alternative (Prerecorded) (Level A) | Not Applicable | No audio or video content. |
| 1.3.1 Info and Relationships (Level A) | Partially Supports | Headings, landmarks, labels, grouped fields and table headers are marked up. Field-level error messages are adjacent to the field (which is marked invalid) but not yet programmatically associated with it. |
| 1.3.2 Meaningful Sequence (Level A) | Supports | |
| 1.3.3 Sensory Characteristics (Level A) | Supports | |
| 1.4.1 Use of Color (Level A) | Supports | |
| 1.4.2 Audio Control (Level A) | Not Applicable | No audio. |
| 2.1.1 Keyboard (Level A) | Supports | |
| 2.1.2 No Keyboard Trap (Level A) | Supports | |
| 2.1.4 Character Key Shortcuts (Level A 2.1 only) | Not Applicable | No character key shortcuts. |
| 2.2.1 Timing Adjustable (Level A) | Partially Supports | Session timeouts are security settings chosen by the customer's administrator; activity extends the idle timeout, but no warning is given before a session ends. |
| 2.2.2 Pause, Stop, Hide (Level A) | Supports | |
| 2.3.1 Three Flashes or Below Threshold (Level A) | Supports | |
| 2.4.1 Bypass Blocks (Level A) | Supports | Skip link and landmarks. |
| 2.4.2 Page Titled (Level A) | Supports | |
| 2.4.3 Focus Order (Level A) | Partially Supports | Dialogs receive focus on opening and return it on closing, but keyboard focus can move out of an open dialog to the page behind it. |
| 2.4.4 Link Purpose (In Context) (Level A) | Supports | |
| 2.5.1 Pointer Gestures (Level A 2.1 only) | Supports | |
| 2.5.2 Pointer Cancellation (Level A 2.1 only) | Supports | |
| 2.5.3 Label in Name (Level A 2.1 only) | Supports | |
| 2.5.4 Motion Actuation (Level A 2.1 only) | Not Applicable | |
| 3.1.1 Language of Page (Level A) | Supports | |
| 3.2.1 On Focus (Level A) | Supports | |
| 3.2.2 On Input (Level A) | Supports | |
| 3.3.1 Error Identification (Level A) | Supports | |
| 3.3.2 Labels or Instructions (Level A) | Supports | |
| 4.1.1 Parsing (Level A) | Supports | |
| 4.1.2 Name, Role, Value (Level A) | Supports | |

### Table 2: Success Criteria, Level AA

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 1.2.4 Captions (Live) (Level AA) | Not Applicable | |
| 1.2.5 Audio Description (Prerecorded) (Level AA) | Not Applicable | |
| 1.3.4 Orientation (Level AA 2.1 only) | Supports | |
| 1.3.5 Identify Input Purpose (Level AA 2.1 only) | Partially Supports | Sign-in fields identify their purpose; fields of the user's own profile (defined by administrators over custom schemas) do not yet. |
| 1.4.3 Contrast (Minimum) (Level AA) | Supports | Verified automatically in both colour schemes. |
| 1.4.4 Resize Text (Level AA) | Supports | To be confirmed by manual testing. |
| 1.4.5 Images of Text (Level AA) | Supports | |
| 1.4.10 Reflow (Level AA 2.1 only) | Partially Supports | Responsive layout; some dense administration editors (form builder, metrics editor) have not been verified at 320 CSS pixels. Data tables scroll horizontally. |
| 1.4.11 Non-text Contrast (Level AA 2.1 only) | Supports | |
| 1.4.12 Text Spacing (Level AA 2.1 only) | Supports | To be confirmed by manual testing. |
| 1.4.13 Content on Hover or Focus (Level AA 2.1 only) | Partially Supports | Chart tooltips cannot be dismissed with the Escape key without moving the pointer or focus. |
| 2.4.5 Multiple Ways (Level AA) | Supports | |
| 2.4.6 Headings and Labels (Level AA) | Supports | |
| 2.4.7 Focus Visible (Level AA) | Supports | |
| 3.1.2 Language of Parts (Level AA) | Supports | |
| 3.2.3 Consistent Navigation (Level AA) | Supports | |
| 3.2.4 Consistent Identification (Level AA) | Supports | |
| 3.3.3 Error Suggestion (Level AA) | Supports | |
| 3.3.4 Error Prevention (Legal, Financial, Data) (Level AA) | Supports | Confirmation before deletes and revocations; imports are checked before they write. |
| 4.1.3 Status Messages (Level AA 2.1 only) | Supports | |

### Table 3: Success Criteria, Level AAA

Not evaluated.

## Revised Section 508 Report

### Chapter 3: Functional Performance Criteria (FPC)

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 302.1 Without Vision | Partially Supports | Built with semantic markup and named controls; not yet verified with screen readers. |
| 302.2 With Limited Vision | Partially Supports | Text resizes with the browser and contrast meets AA; reflow of some administration editors not yet verified. |
| 302.3 Without Perception of Color | Supports | |
| 302.4 Without Hearing | Not Applicable | No audio. |
| 302.5 With Limited Hearing | Not Applicable | No audio. |
| 302.6 Without Speech | Not Applicable | Speech is not used. |
| 302.7 With Limited Manipulation | Supports | Fully keyboard operable; no timed or multipoint gestures. |
| 302.8 With Limited Reach and Strength | Supports | |
| 302.9 With Limited Language, Cognitive, and Learning Abilities | Partially Supports | Consistent layout, plain-language messages and confirmations; session timeouts give no advance warning. |

### Chapter 4: Hardware

Not applicable: the product is software.

### Chapter 5: Software

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 501.1 Scope – Incorporation of WCAG 2.0 AA | See WCAG 2.x section | The product is web content; see the WCAG tables. |
| 502 Interoperability with Assistive Technology | Not Applicable | Web application; platform accessibility services are provided by the browser. |
| 503.2 User Preferences | Supports | Follows the system colour scheme and reduced-motion setting, browser text size and zoom. |
| 503.3 Alternative User Interfaces | Not Applicable | |
| 503.4 User Controls for Captions and Audio Description | Not Applicable | No media. |
| 504.2 Content Creation or Editing | Partially Supports | Administrators create forms with the form builder: every field gets a visible label, hints and validation messages, so generated forms conform as in the WCAG tables. The builder does not prompt for accessibility information beyond labels and help text. |
| 504.2.1 Preservation of Information Provided for Accessibility in Format Conversion | Not Applicable | Forms are not converted to other formats. |
| 504.2.2 PDF Export | Not Applicable | No PDF export. |
| 504.3 Prompts | Partially Supports | Labels are required for every field; help text is optional. |
| 504.4 Templates | Not Applicable | |

### Chapter 6: Support Documentation and Services

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 602.2 Accessibility and Compatibility Features | Supports | Described in this report and the WCAG self-audit; ENS to add to the product documentation site. |
| 602.3 Electronic Support Documentation | Partially Supports | The documentation site has not been evaluated against WCAG; *ENS to evaluate.* |
| 602.4 Alternate Formats for Non-Electronic Support Documentation | Not Applicable | Documentation is electronic only. *ENS to confirm.* |
| 603.2 Information on Accessibility and Compatibility Features | *ENS to complete* | Support services' handling of accessibility questions. |
| 603.3 Accommodation of Communication Needs | *ENS to complete* | |

## Legal disclaimer

*To be provided by ENS.* This draft is a self-assessment and must not be represented as an
independent evaluation.
