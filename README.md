# EnterSend

Android accessibility service for phones with a physical keyboard (primary target: Unihertz Q25, Android 14).
In chat text fields, **Enter sends** the message and **Shift+Enter inserts a newline**. Everywhere else Enter behaves normally.

- Package: `com.meowgi.entersend`
- Kotlin, Gradle, minSdk 29, targetSdk 35
- No internet permission; the database and button screenshots are excluded from cloud backup and device transfer.

## Build

Requirements: JDK 17+ and an Android SDK with platform 35 (`ANDROID_HOME` set, or `sdk.dir` in `local.properties`).

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Unit tests run on the JVM with Robolectric (no device needed).

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
  (no `signingConfig`, no keystore in the repo — sign outside with your own key).
  R8/minify is **off** for release; no keep rules are required for the accessibility
  service or Room.

## Install and enable

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open EnterSend. The first-run setup has three steps, each with a check mark:

1. **Turn on EnterSend**: the button opens EnterSend's page in Accessibility settings (or the Accessibility
   list on builds without that page). Turn it on and allow it. On Android 13+ the app also asks once for
   notification permission, used only for the "EnterSend is off" notice.
2. **Press Enter and Shift+Enter** in the focusable box.
3. **Try it**: type in the test box and press Enter (it is sent) and Shift+Enter (new line).

Everything is reachable with the physical keyboard: arrow keys or Tab move focus (subtle Material tint;
system orange focus highlight is disabled), Enter presses the focused control.

## Auto-focus (0.3.13)

Per-app only (Apps → app sheet → Auto-focus: On/Off, default Off). When on, EnterSend finds a
single editable field (or a taught identity) and focuses it once per window appearance so the
hardware keyboard can type immediately. Multiple/zero fields prompt a short teach; if a11y
focus/click both fail, a gesture tap fallback is used and labelled in the sheet. Optional step
after send-button teach: “Also auto-focus…?”. Never re-steals focus; skips system apps.

## App UI (0.3.8)

Material 3 shell with light/dark (and dynamic color on Android 12+), built for the Q25’s short
square screen and hardware keyboard (D-pad / Tab friendly focus tint).

Bottom navigation:

1. **Home** — service status, optional **Usage Access** card, active profile, shortcuts to setup and apps.
2. **Apps** — same PackageManager listing as 0.3.3 (MAIN+LAUNCHER, system-app filter / Show system apps),
   with a blocking loading spinner while the list loads on a background thread, then one main-thread swap.
3. **Profiles** — save / switch / rename / delete full config snapshots (different keyboard heights).
4. **Settings** — new-app prompts toggle, Usage Access backup, export/import JSON, open setup, **Diagnostics**, version.

Settings changes still apply to the running accessibility service immediately. Teach overlays keep
the same behavior; only the visuals were polished.

### New-app prompts

For every **new open** of a launcher-visible non-system app that is still undecided, EnterSend
shows a high-importance heads-up notification on channel `teach_prompt_alert` (Teach / Remind later /
Off). Prompt foreground detection uses `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED` and the
**active/focused** `TYPE_APPLICATION` window — never IME, accessibility overlays, SystemUI, or
EnterSend itself (those never clear the current app). **Enter** always uses the focused input
field’s package (`findFocus(FOCUS_INPUT)`), independent of prompt foreground. An optional
**Usage Access** backup (Home card when not granted; auto-enabled once granted) cross-checks
`MOVE_TO_FOREGROUND` on each a11y event and when the screen turns on. Diagnostics logs prompt
sources (`a11y/windows`, `a11y/root`, `a11y/event`, `usage`) and every Enter (package, rule,
consumed). If notifications are denied or the channel is blocked, a top accessibility overlay banner
appears instead. Tapping either opens a compact **accessibility overlay** chooser over the current
app (not a dialog activity). If the service is disconnected, EnterSend opens the Apps action sheet
instead. Session dedupe prevents spam inside one visit; leaving and returning asks again until the
user decides. System apps stay Off and are never prompted. Apps → **Ask again on open** clears
“don’t ask again”.

Select an app for: Teach / Re-teach, mode Auto, Learned, Pass-through, Off, Ask again, and Reset.
System apps are hidden until you check **Show system apps**. Browsers, notes and email apps are
listed as "Never" and cannot be enabled.

If the service is off or was killed, a red banner appears at the top of the app and a persistent notification
offers a one-tap fix. The notification is posted when the service is unbound, when the app is opened, and by a
15-minute periodic job.

## What Enter does

When Enter is pressed (key repeats and duplicate events count as one press):

1. Pass through unless the focused field is chat-like: not a password, URL, email, search or filter field,
   not in a browser, notes or email app, and with a chat signal (a known messenger, a "short message" input
   type, a message/chat-like view id or hint, or a field the user taught).
2. Pass through if the app is set to **Off** or **Pass-through**. Apps where Enter already sends
   (Discord, Slack, Teams) default to Pass-through.
3. **Shift+Enter** inserts a newline at the cursor, replacing any selection.
4. **Enter in an empty field** is swallowed and nothing is tapped (the send button is often a mic then).
5. Otherwise find the send button, in this order:
   1. the rule the user taught for this app (view id / text / content description, or class path at the taught spot);
   2. auto-detect: a clickable node near the field labelled send (English or Turkish: Send, Gönder, …);
   3. the saved tap spot: try edge-anchored, absolute, then proportional candidates; tap the first whose
      live crop still matches the taught crop (no blind taps);
   4. none found: Enter passes through.
6. After a click, the field must empty within ~1.7 s to count as a success (short vibration); otherwise it
   counts as a failure. While a send is unconfirmed, further Enter presses are swallowed.

### Duplicate key events

On the Q25 every physical press arrives twice: the real event and a zero-duration copy with deviceId -1
about 15 ms later. `KeyGate` gives the copy the same answer as the real press (consumed or passed),
so a press acts at most once. A deviceId -1 Enter with no real press nearby is passed through untouched.

## Teaching an app

When Enter finds no working send rule in a chat field, a small card appears at the bottom:
"Didn't find send. Teach this app?" It never takes focus, so typing keeps going to the app.
It appears at most once per app per day.

| Key        | Ask card                        | Teaching bar | "Is this the send button?" |
|------------|---------------------------------|--------------|----------------------------|
| **Alt+Y**  | yes, start teaching             | –            | yes, save                  |
| **Alt+N**  | no, never ask again for this app | –           | no, try again              |
| **Esc**    | close (ask later)               | cancel       | cancel                     |
| any other key | goes to the app; hides the card (ask later) | goes to the app | goes to the app |

Hold Alt while pressing Y or N. Plain letters are never consumed, so typing "yes", "no", "Y" or "N"
in the chat always types them. The ask card disappears after 8 s (ask later). A "no" is stored in the
database and survives reboots. The cards also have touch buttons.

Teaching: a slim bar at the top says "Send a message now". Type a message and tap the app's send button.
EnterSend records the tapped node (view id, text, content description, class path) and the tap spot with a
cropped screenshot taken just before the tap, the screen orientation and the app window size, then asks
"Is this the send button?" with the crop. It saves only on yes; no starts over.

## Storage

Room database `entersend.db`, schema v1, exported to `app/schemas/`. Every schema change needs a
migration in `AppDb.MIGRATIONS` plus a test in `AppDbMigrationTest` that upgrades real saved data;
destructive fallback is never enabled. Button crops are PNGs in app-private `files/crops/`. An app's rule
is loaded into memory when the app comes to the foreground; counters are written on a background thread.
Rules and crops of uninstalled apps are deleted when the app process starts.

## Project layout

```
app/src/main/java/com/meowgi/entersend/
  core/      pure logic: KeyGate (dedupe), ChatFieldGuard, SendFinder, CropMatch, AppLists, TextEdit, Model
  service/   EnterSendService (entry point), EnterHandler (Enter decisions), TeachFlow (cards),
             RuleStore (in-memory rules, background writes), Device/A11yDevice and Overlay/CardOverlay seams
  data/      Room: AppRule entity, RuleDao, AppDb (migrations), CropFiles
  ui/        MainActivity (settings), SetupActivity (first run), AppRows, AppListAdapter
  ServiceWatch.kt   service-off notification, periodic check job
app/schemas/ exported Room schemas (v1)
app/src/test/ Robolectric behavior tests
```

Tests drive key events through `EnterSendService.onKeyEvent` (the real accessibility input path), with a fake
`Device` (screen) and `Overlay` (cards) and an in-memory Room database.

## Permissions

The only permission the user grants is Accessibility. The manifest also declares `VIBRATE` (install-time,
no prompt) for the buzz after a send and `POST_NOTIFICATIONS` for the service-off notification
(Android 13+ asks once; everything else works if it is denied). There is no `INTERNET` permission;
`ManifestTest` fails if any other permission appears.

## Known limits

- When every tap-spot candidate fails the live crop check after Enter was already taken, Enter is re-delivered
  with the accessibility IME-Enter action. Standard Android text fields treat it like Enter; custom editors may not.
  A null screenshot (AccessibilityService ~1/s rate limit) is retried once after ~350 ms or reused if a shot
  younger than 1 s is cached — it does not silently insert a newline.
- Screenshots need Android 11+; on Android 10 the tap-spot fallback is skipped.
- Shift+Enter rewrites the field text through accessibility, so rich-text spans (e.g. @mentions) may be lost.
- Alt+Y / Alt+N must be held as chords. While the ask or confirm card is up, the Alt symbol on the Y and N keys
  can't be typed (the card takes it); once the card is gone they type normally.
- Teaching needs the app to report the tap as an accessibility click (`TYPE_VIEW_CLICKED`). Apps that don't
  (some Compose or game-engine UIs) can't be taught; Enter then passes through.
- The crop is taken from the last screenshot before the tap (taken 0.4 s after the last text change). If the
  user taps send faster than that after typing the first character, the crop may show the mic button; the
  confirm card shows the crop so the user can answer no and retry.
- Cleanup of uninstalled apps relies on package visibility of launchable apps (`<queries>`). A rule for an app
  without a launcher entry is treated as uninstalled and removed.
- Chat detection uses lists of known messengers, browsers, notes and email apps (`core/AppLists.kt`) plus
  view-id/hint words in English and Turkish. Unknown apps need a message-like field or teaching.
- The periodic service check is not persisted across reboots; it is re-scheduled whenever the app process starts
  (which happens at boot when the service is enabled).
