#!/usr/bin/env node
/*
 * Sends ONE data-only FCM push straight to a handset, carrying a dedupe key you choose.
 *
 * This exists for definition-of-done item 3, which is the whole milestone: a reminder must
 * arrive exactly once even when BOTH paths deliver it. Proving that needs the two paths to
 * race on a real phone, and the only way to make the FCM half happen on demand — rather than
 * waiting for 09:00 and hoping — is to send the push by hand.
 *
 * It deliberately does NOT go through the server. The point is to simulate the server's push
 * arriving late, after the phone's own alarm has already fired and claimed the key.
 *
 *   # the token is printed at launch by debug builds only (see WishKaroApplication)
 *   adb shell setprop log.tag.Notifications VERBOSE
 *   adb shell am force-stop in.wishkaro.app
 *   adb shell monkey -p in.wishkaro.app -c android.intent.category.LAUNCHER 1
 *   adb logcat -d -s Notifications:V | tail -1 | sed 's/.*FCM token: //' > /tmp/fcm-token
 *
 *   node scripts/m2-fake-push.cjs <dedupeKey> /tmp/fcm-token
 *
 * Reads the service account from ~/.secrets/wishkaro-service-account.json — OUTSIDE the
 * repo, which is public. Override with GOOGLE_APPLICATION_CREDENTIALS.
 */
const { initializeApp, cert } = require('firebase-admin/app');
const { getMessaging } = require('firebase-admin/messaging');
const fs = require('fs');
const os = require('os');
const path = require('path');

const [dedupeKey, tokenFile] = process.argv.slice(2);
if (!dedupeKey || !tokenFile) {
  console.error('usage: node scripts/m2-fake-push.cjs <dedupeKey> <file-with-fcm-token>');
  process.exit(2);
}

const keyPath =
  process.env.GOOGLE_APPLICATION_CREDENTIALS ||
  path.join(os.homedir(), '.secrets', 'wishkaro-service-account.json');

initializeApp({ credential: cert(JSON.parse(fs.readFileSync(keyPath, 'utf8'))) });

getMessaging()
  .send({
    token: fs.readFileSync(tokenFile, 'utf8').trim(),
    // Data-only, exactly like the server's. A `notification` payload would be drawn by the
    // system without ever reaching the app, so neither the dedupe check nor the deep link
    // would run — which would make this prove nothing at all.
    data: {
      dedupeKey,
      reminderId: 'hand-sent',
      offset: process.env.OFFSET || 'D0',
      type: 'REMINDER',
      title: process.env.TITLE || 'Hand-sent reminder',
      body: process.env.BODY || 'Sent by scripts/m2-fake-push.cjs',
      personId: process.env.PERSON_ID || '',
      personName: process.env.PERSON_NAME || '',
      eventType: 'BIRTHDAY',
      sentiment: process.env.SENTIMENT || 'CELEBRATORY',
      deepLink: process.env.PERSON_ID ? `wishkaro://person/${process.env.PERSON_ID}` : 'wishkaro://home',
    },
    android: { priority: 'high' },
  })
  .then((id) => console.log('sent', id))
  .catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
