# VocaGuard Device Testing Checklist

Manual QA checklist for verifying all major features on a real Android device (minSdk 29 / Android 10+).

---

## 1. Initial Setup & Onboarding

- [ ] App installs cleanly from debug APK or Play Store internal track
- [ ] Onboarding screen appears on first launch (3-page walkthrough)
- [ ] Tapping through all 3 pages completes onboarding and lands on Home tab
- [ ] Onboarding does **not** reappear on subsequent launches
- [ ] App requests runtime permissions at the right time (RECORD_AUDIO, READ_PHONE_STATE, POST_NOTIFICATIONS)

---

## 2. Permissions & System Access

### 2a. Standard permissions
- [ ] Phone State permission granted → status card turns green
- [ ] Record Audio permission granted → status card turns green
- [ ] Post Notifications permission granted → notifications appear on detection
- [ ] Denying a permission shows a graceful degraded state (no crash)

### 2b. Special permissions (require user to navigate to system settings)
- [ ] **Call Screening role**: grant via Settings → tap "Configure Call Screening" in Settings tab → VocaGuard listed as active screener
- [ ] **Accessibility Service**: enable via Settings → Accessibility → VocaGuard appears in service list
- [ ] **Draw Overlay**: grant via Settings → "Configure Overlay Permission" → overlay displays during scam detection
- [ ] **Notification Access**: grant via Settings → "Message Scanning" card shows green / no error banner

### 2c. Permissions screen completeness
- [ ] Settings tab → "Message Scanning" card shows red error banner when Notification Access is missing
- [ ] "Open Notification Access" button in the error banner navigates to the correct system screen
- [ ] After granting access and returning, the error banner disappears (may need to scroll/recompose)

---

## 3. Call Screening (Pre-call)

- [ ] Receive a call from an unknown number → call screening triggers
- [ ] Known scam number (manually added via Report Scam) → call is blocked or flagged
- [ ] Whitelisted number → call passes through without any alert
- [ ] Call from saved contact → no alert fired

---

## 4. In-Call Scam Detection

- [ ] Start a call and speak scam phrases ("IRS", "gift card", "arrest warrant") → alert fires
- [ ] Alert sound plays (if enabled in Settings)
- [ ] Vibration fires (if enabled in Settings)
- [ ] TTS reads the scam type aloud (if enabled in Settings)
- [ ] **Overlay** appears over the in-call screen with scam type and confidence
- [ ] Overlay auto-dismisses after ~8 seconds
- [ ] "Block Caller" action in the notification adds number to blocked list
- [ ] Call transcript saved to History tab after call ends

---

## 5. WhatsApp / Telegram Message Scanning

**Prerequisites:** Notification Access granted, "Scan messages" toggle ON in Settings.

- [ ] Receive a WhatsApp message with a benign text → no alert
- [ ] Receive a WhatsApp message containing scam keywords (e.g. "You won a lottery prize, claim now") → notification is dismissed and a VocaGuard warning notification appears
- [ ] Same test with Telegram
- [ ] Scam message with empty/blank preview (notifications with previews disabled in WhatsApp) → no crash, no false alert
- [ ] Toggle "Scan messages" OFF → scam WhatsApp message passes through unblocked

---

## 6. OTA Model Update

- [ ] Settings tab → "Detection Model" card → tap "Check for Model Update"
- [ ] Status shows "Checking…" then resolves
- [ ] If bundled model is older than remote version.json → "Update downloaded. Restart app to apply."
- [ ] If already up to date → "Model is up to date (v1.x)"
- [ ] After restart → log shows "ML model loaded successfully" (check via `adb logcat | grep TFLiteScam`)
- [ ] Invalid / unreachable URL → graceful error message, no crash

---

## 7. Family Guard Mode

**Prerequisites:** SEND_SMS and CALL_PHONE permissions granted, Family Guard enabled, at least one contact added.

- [ ] Enable Family Guard → caregiver contact added successfully
- [ ] Set senior name (e.g. "Grandma") → saved and persists after restart
- [ ] Trigger a scam detection (speak scam phrases during a call)
- [ ] Contact receives SMS alert containing the senior name and scam type
- [ ] Webhook URL set → HTTP POST fires on scam detection
- [ ] "Send Test Alert" button → SMS received by contact (no real call placed)
- [ ] Family Dashboard tab → alert entry appears with timestamp, type, confidence
- [ ] Deep-link `vocaguard://alert?name=Grandma&type=IRS_SCAM&conf=0.87&ts=...` → opens app at Family Dashboard

---

## 8. Senior Mode

- [ ] Enable Senior Mode in Settings → Home tab switches to SeniorHomeScreen
- [ ] All text is noticeably larger
- [ ] Switching tabs plays TTS voice announcement of the tab name
- [ ] Disable Senior Mode → normal HomeScreen restored

---

## 9. History Tab

- [ ] Transcripts from test calls appear in History
- [ ] Search bar filters by keyword
- [ ] "Scams only" chip hides legitimate calls
- [ ] Scam type dropdown filter works
- [ ] Delete transcript → confirmation dialog appears → transcript removed
- [ ] Export as Text → share sheet appears with readable content
- [ ] Export as CSV → share sheet with comma-separated values
- [ ] Pagination: if >30 entries, "Load more" shows next page

---

## 10. Home Tab & Widget

- [ ] Stats card shows correct scam count and total calls for last 30 days
- [ ] Trend chart shows 7-day bar chart (blue = clean, red = scam)
- [ ] Home screen widget shows "Protected" when all permissions granted
- [ ] Home screen widget shows "Setup Required" when any permission missing
- [ ] Widget subtitle shows "Blocked N scam(s) today" if scams detected today
- [ ] Widget refreshes after a scam is detected (within ~30 min or on next app open)
- [ ] Tapping the widget opens VocaGuard

---

## 11. Settings & Preferences

- [ ] Sensitivity slider adjusts confidence threshold (low = more alerts, high = fewer)
- [ ] Language dropdown changes STT language (verify spoken "IRS" triggers detection in en-US; spoken Hebrew triggers in iw-IL)
- [ ] Alert Sounds toggles (TTS / alarm / vibration) persist after restart
- [ ] App Theme (light/dark/system) applies immediately
- [ ] Alert Filters: disable "IRS Scam" → IRS detection no longer fires alert sound
- [ ] Community Blocklist → Sync Now → success message with count of numbers imported
- [ ] Backup → Export → JSON file shared; Import → settings restored on fresh install

---

## 12. Multilingual Detection (EN / RU / HE / AR / ES / FR)

For each language, trigger a call or send a WhatsApp message containing the relevant scam phrases.

| Language | Test phrase | Expected result |
|---|---|---|
| English | "You owe the IRS money, pay now or face arrest" | IRS Scam detected |
| Russian | "Вам нужно срочно оплатить долг в налоговую" | IRS Scam detected |
| Hebrew | "חוב מס לרשות המסים, פעל עכשיו" | IRS Scam detected |
| Arabic | "لديك ديون ضريبية مع مصلحة الضرائب" | IRS Scam detected |
| Spanish | "Tiene una deuda fiscal con hacienda, pague inmediatamente" | IRS Scam detected |
| French | "Vous avez une dette fiscale avec les impôts, agissez maintenant" | IRS Scam detected |

---

## 13. Edge Cases & Stability

- [ ] App survives rotation during an active call
- [ ] App survives process kill and restart mid-call
- [ ] No crash when Notification Access is revoked while app is running
- [ ] No crash when audio permission is revoked mid-call
- [ ] Cold start time < 3 seconds on a mid-range device
- [ ] No ANR after 10 minutes of idle background monitoring
- [ ] Battery usage after 1 hour of monitoring: check Settings → Battery → VocaGuard shows "Low" or better

---

## 14. Play Store Pre-submission Checks

- [ ] Signed AAB builds without errors: `./gradlew bundleRelease`
- [ ] ProGuard mapping file generated at `app/build/outputs/mapping/release/mapping.txt`
- [ ] `adb install` of the release APK succeeds (no signature mismatch)
- [ ] App content rating questionnaire filled in Play Console (voice/VOIP app)
- [ ] Privacy policy URL set in Play Console (points to `assets/privacy_policy.html` hosted or GitHub Pages)
- [ ] Target SDK 35 (or latest) set in `build.gradle.kts`
- [ ] Screenshots captured: phone (portrait) and 7-inch tablet
- [ ] Short description (80 chars): "AI-powered scam call & message detector with real-time alerts"
- [ ] Full description written covering: call screening, in-call STT, WhatsApp/Telegram scanning, Family Guard, multilingual support
