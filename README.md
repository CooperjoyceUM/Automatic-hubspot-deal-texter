# Utebuild Texter

A personal Android app for my Samsung Galaxy S24. About every 15 minutes it checks HubSpot for new
Utebuild deals assigned to me. If a deal qualifies, it texts the customer from my own SIM to say when I'll
call, then adds a note to the deal with the exact text it sent.

The app is sideloaded as a debug-signed APK. It is not on the Play Store.

## What it does

**Which deals qualify.** All of these must be true:
- Pipeline is **Trade & Rec** (`658412132`) and stage is **Discovery** (`967474456`).
- Deal owner is me (`1101472483`).
- `utebuilder_submission_link` is filled in.
- The deal was created after the moment I first switched the app on.
- The deal hasn't been texted already.

All of these IDs can be changed in Settings.

**When it texts.** Based on the deal's `createdate` in Sydney time. Daylight saving is handled.

| Deal created | Text says |
|---|---|
| Weekday, 3:00pm to midnight | "I'll call you **tomorrow**", or "**on Tuesday**" if tomorrow is a weekend or holiday |
| Weekday, midnight to 7:00am | "I'll call you **this morning**" |
| Saturday or Sunday, any time | "I'll call you **on Monday**", or a later day if Monday is a public holiday |
| NSW public holiday, any time | Same as a weekend: the next working day |
| Weekday, 7:00am to 3:00pm | No text |

Texts go out as soon as the app finds the deal, even overnight.

The call **day** comes from `createdate`. The **wording** is worked out at the moment the text is sent.
For example, a deal created at 11:58pm and texted at 12:10am says "this morning", not "tomorrow".
If the promised day has already passed (say the phone was off all weekend), the app doesn't send the text.
It notifies me to call them myself instead.

**Avoiding duplicate texts:**
1. A local Room database records every deal it has handled. A deal marked `SENT` is never texted again.
2. Before sending, it checks the deal's notes in HubSpot for `[Utebuild auto-text sent]` and skips the
   deal if it finds one. This still works after a reinstall.
3. The deal is marked `SENDING` before the text goes out. If the app is killed mid-send, or the phone never
   confirms the text, the deal shows as **Unconfirmed**. Nothing is resent automatically. I choose
   **Mark as sent** or **Retry** in the app.
4. A lock stops the scheduled check and **Run check now** from running at the same time.

**After a successful send,** the app adds a note to the deal with the exact text, the number it went to and
the time. It doesn't change any deal properties or touch the existing call tasks. If the note fails to
save, it's retried on the next check, and the text is not resent.

**Failures.** The app shows a notification and does **not** mark the deal as texted when:
- the contact has no mobile number,
- the SMS fails, or
- HubSpot returns an error.

It retries on each check. SMS and HubSpot failures stop after 6 attempts (about 1.5 hours). A missing
mobile number keeps being retried, so the text goes out once the number is added, up until the promised
call day passes. Repeat notifications for the same problem are held back for 6 hours.

**Test mode** is on by default. While it's on, every text goes to **my own number**, a yellow banner
says so, and no HubSpot notes are written. You can turn notes on for testing in Settings. Live mode stays
blocked until the qualifying-questions placeholder has been replaced.

## Getting the APK

The Android SDK download host was blocked in the build environment, so GitHub Actions builds the APK.
The workflow is in `.github/workflows/build.yml`.

1. On GitHub, go to **Actions → Build APK** and open the latest run on this branch.
2. Under **Artifacts**, download **utebuild-texter-debug-apk** (a zip file) and extract `app-debug.apk`.

To build it yourself you need JDK 17 and the Android SDK:

```bash
./gradlew :core:test :app:assembleDebug     # APK → app/build/outputs/apk/debug/app-debug.apk
CORE_ONLY=1 ./gradlew :core:test            # just the logic tests, no Android SDK needed
```

The repo includes a fixed debug keystore (`app/debug.keystore`). Every build is signed with the same key,
so a new APK installs over the old one and keeps your settings and history.

## Installing on the Galaxy S24

1. **Turn off Auto Blocker for now.** Settings → Security and privacy → Auto Blocker → Off.
   While it's on, Samsung blocks apps from outside the Galaxy Store. You can turn it back on after
   installing.
2. Copy `app-debug.apk` to the phone and tap it in **My Files**. When asked, allow **Install unknown apps**
   for My Files (or for Chrome, if you downloaded it there). Then tap **Install**. If Play Protect warns
   about an unknown app, choose **More details → Install anyway**.

### Allowing restricted settings (needed for SMS)

Android treats SMS permission as a *restricted setting* for sideloaded apps, so it may refuse to grant it
the first time.

1. Open the app and, in the setup checklist, tap **Allow** next to *SMS permission*.
2. If you see **"Restricted setting – for your security, this setting is currently unavailable"**:
   - Tap **Open App info** in the app, or go to Settings → Apps → Utebuild Texter.
   - Tap **⋮** (top right) → **Allow restricted settings**, then confirm with your PIN or fingerprint.
3. Go back to the app and tap **Allow** again. Choose **Allow** for sending SMS.
4. Allow **Notifications** too.

### Keeping it running in the background (Samsung battery settings)

Samsung puts background apps to sleep quite aggressively. Do all three of these:

1. In the app, tap **Fix** next to *Battery: unrestricted*, then **Allow**.
   Or go to Settings → Apps → Utebuild Texter → Battery → **Unrestricted**.
2. Settings → Battery → **Background usage limits** → **Never sleeping apps** → **+** → tick
   **Utebuild Texter** → **Add**. On some One UI versions this is under Settings → Device care → Battery.
3. On the same Background usage limits screen, make sure Utebuild Texter is **not** listed under
   *Sleeping apps* or *Deep sleeping apps*. Leave *Put unused apps to sleep* on if you want; step 2 exempts
   this app.

Checks run through Android WorkManager about every 15 minutes, which is Android's minimum. In Doze mode,
while the phone sits idle with the screen off, they can be a bit later. Checks restart by themselves after
a reboot.

## Setting it up

### 1. Create the HubSpot token

In HubSpot, go to Settings → Integrations → **Private Apps** → **Create a private app**. A service key
works too. Give it these scopes:

| Scope | Used for |
|---|---|
| `crm.objects.deals.read` | Searching for deals and reading their notes |
| `crm.objects.contacts.read` | Getting the customer's `mobilephone` / `phone` |
| `crm.objects.owners.read` | The **Test HubSpot connection** button |
| `crm.objects.deals.write` | Creating the note linked to the deal |

HubSpot doesn't have a separate "notes" scope. A note linked to a deal uses the deal's write scope. The
app never updates any deal properties. If saving a note fails with *"missing scope"*, the notification
names the scope to add.

### 2. Fill in Settings

1. Open **Settings** and paste the token into the token field, then tap **Save**. The token is stored in
   EncryptedSharedPreferences, with the key kept in the Android Keystore. It's never shown again or logged.
2. Check the pre-filled IDs: pipeline `658412132` (Trade & Rec), Discovery stage `967474456`, owner
   `1101472483`, and link property `utebuilder_submission_link`. All of them can be edited.
3. Enter **your own mobile number**. In test mode, every text goes there.
4. Tap **Save & test HubSpot connection**. It should show your name and a deal count.
5. Edit the **message template** and the **qualifying questions**. Tap **Preview message** to see the
   result and how many SMS parts it uses.
6. Back on the home screen, switch **Automatic checking** on. The moment you do this is recorded, and only
   deals created after it will ever be texted. Use **Run check now** to run a check straight away.

### 3. Go live

When the test texts look right:
1. Replace the placeholder qualifying questions.
2. Turn **Test mode** off and tap **Save**. The banner turns red and says **LIVE**.

Deals already handled in test mode are not texted again.

## Updating NSW public holidays each year

Go to Settings → **NSW public holidays**. There's one date per line, in the format `YYYY-MM-DD Name`.
Add next year's dates from <https://www.nsw.gov.au/about-nsw/public-holidays> and tap **Save**. No
rebuild is needed. The app sends a reminder notification if the list has nothing for the current year,
or, in December, for the next year.

The built-in list covers **2026–2028** and is in
`core/src/main/kotlin/com/utemaster/dealtexter/core/NswHolidays.kt`. **Reset to built-in list** goes back
to it. Bank Holiday in August isn't included because it isn't a state-wide public holiday.

## Project layout

```
core/   Plain Kotlin, no Android, unit tested
        CallTiming       which day to promise, and the wording
        NswHolidays      holiday list, parser and working-day calendar
        PhoneNumbers     Australian numbers → +61
        MessageTemplate  message template and the HubSpot note format
        DealProcessor    the check → dedupe → send → note workflow, plus retries
app/    Android
        hubspot/HubSpotClient     CRM v3 search, v4 associations, batch read, note create
        sms/AndroidSmsGateway     SmsManager (multipart), waits for sent confirmation
        data/                     Room records, settings and the encrypted token
        work/CheckWorker          WorkManager periodic job and "Run check now"
        ui/                       Compose home and settings screens
```

The unit tests are in `core/src/test`. They cover:
- Friday afternoon
- Saturday and Sunday
- 2am Monday, including when Monday is a holiday
- the 3pm and 7am boundaries
- Easter, Christmas and New Year holidays
- days when daylight saving starts and ends, plus the weekdays either side
- phone number normalisation
- templates
- the dedupe, retry and failure paths
