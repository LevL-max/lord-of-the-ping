#!/usr/bin/env bash
# Rollback of the 2026-10-25 tweaks on the Anker Nebula Capsule 3 projector (SDMC D2426, Android TV 14,
# Wi-Fi Home 192.168.1.77). Run from Git Bash on the laptop. Each block is independent.
# Before-state of all settings: settings-before-2026-10-25.txt next to this script.
export MSYS_NO_PATHCONV=1
ADB="/c/platform-tools/adb.exe -s ${TV:-192.168.1.77:5555}"

# 1. Animations (were window 1.0, transition 1.0, animator 0.6)
$ADB shell settings put global window_animation_scale 1.0
$ADB shell settings put global transition_animation_scale 1.0
$ADB shell settings put global animator_duration_scale 0.6

# 2. Google Assistant (katniss), disabled at the user's request: its always-on "Hey Google" detector cost ~26% of
#    one core even with the mic muted, plus ~300 MB. The system clears the assistant settings on disable, so they
#    are put back too. Microphone access was off for a while and is ON again, as before, so nothing to undo there.
$ADB shell pm enable com.google.android.katniss
$ADB shell settings put secure voice_interaction_service com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService
$ADB shell settings put secure assistant com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService
$ADB shell settings put secure voice_recognition_service com.google.android.katniss/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService

# 3. Nebula Log Service upload flag (was 1)
$ADB shell settings put global Anker_LogService_CanUpload 1

# 4. Disabled apps
for p in com.google.android.tvrecommendations com.google.android.backdrop com.cltv.fast \
         com.zhixin.logservice.nebula fusion.android.tv.demo com.google.android.youtube.tvmusic \
         com.google.android.play.games com.google.android.videos com.google.android.apps.nbu.smartconnect.tv \
         com.amazon.amazonvideo.livingroom; do
  $ADB shell pm enable "$p"
done

# 4b. Live TVx: the firmware (com.mediatek.tv.service) re-enables it at every boot, so instead of staying
#     disabled it was barred from running in the background.
$ADB shell cmd appops set com.cltv.fast RUN_ANY_IN_BACKGROUND allow

# 5. Screensaver (was Backdrop; needs block 4 first)
$ADB shell settings put secure screensaver_components com.google.android.backdrop/.Backdrop

# 7. Per-app language (was: follow the system, en-GB). Russian for YouTube and Filmix, so their own mic buttons
#    recognise Russian while the system stays English. No --locales = back to the system language.
$ADB shell cmd locale set-app-locales com.google.android.youtube.tv --user 0
$ADB shell cmd locale set-app-locales net.filmix.filmix --user 0

# 6. Launcher: back to Android TV Home
$ADB shell pm enable com.google.android.tvlauncher
$ADB shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
# Optional: remove Lord of the Ping itself.
# $ADB shell pm uninstall com.lordoftheping.home
#
# Without adb, from the remote: Settings -> Apps -> See all apps -> Show system apps ->
#   "Android TV Home" -> Enable, then press Home and pick it.
