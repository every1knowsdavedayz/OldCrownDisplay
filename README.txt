OLD CROWN DISPLAY v0.5 — NETWORK CONTROL CENTRE
================================================
Android 6.0 (API 23) and newer | source project, not a compiled APK

WHAT IT DOES
- Main display switches between continuous live stream and looping local playlist.
- Rotating picture adverts at the right, independent scrolling ticker at the bottom.
- The native Android TV remote opens an on-screen menu.
- A password-protected LAN webpage controls mode, stream selection, stream URLs,
  ticker text, side panel/ticker size, rotation timings and ticker speed.
- Upload and remove local posters and videos from a Windows laptop or phone.

INSTALL / BUILD
1. Open this entire folder in Android Studio on Windows.
2. Allow Gradle to download the required Android plugin and ExoPlayer 2.18.7.
3. Build > Build APK(s), and install the APK on your Android 6 box.
4. Grant storage permission on first launch.
5. Connect the Android box and the controlling PC/phone to the same trusted LAN.
6. Using the Android TV remote, press OK or MENU and choose
   NETWORK REMOTE — Address & password.
7. On the laptop, open http://<android-box-local-IP>:8765/
   Use username 'admin' and the randomly generated password shown on the TV.
   The password persists across app restarts.

CONTENT FOLDERS
/sdcard/OldCrownDisplay/main/       local videos and pictures (alphabetical, looping)
/sdcard/OldCrownDisplay/ads/        pictures rotating on the right
/sdcard/OldCrownDisplay/streams.txt direct authorised HTTP/HLS/DASH links
/sdcard/OldCrownDisplay/ticker.txt  ticker text

IMPORTANT LIMITS
- Uploaded file limit: 80 MB per file. For large video files use USB/file transfer.
- Both screens must be on the same network, and the Android app must be running.
- Some guest Wi-Fi networks prevent device-to-device connections (client isolation).
- The HTTP admin page is for trusted local networks only. NEVER forward port 8765,
  expose it publicly, or use it over untrusted public Wi-Fi: Basic auth over HTTP
  is not encrypted.
- Remote page does not preview live video. It controls what the TV displays.
- This source has not been compiled or tested on the actual Android box.
- Network streaming must be direct supported URLs; DRM services, protected
  websites and unsupported codecs are not bypassed.
- Very old Android boxes vary in decoding support; test actual stream formats.

PROJECT FILES
app/src/main/java/.../MainActivity.java — playback, settings, remote actions
app/src/main/java/.../RemoteServer.java — LAN web server and Basic authentication
app/src/main/assets/remote.html — standalone control webpage

This is an incremental update based on v0.4, preserving playback and on-TV controls.
