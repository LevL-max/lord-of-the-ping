#!/usr/bin/env bash
# Rollback of the 2026-11-01 tweaks on the Xiaomi box (MiTV-AFKR0 "jaws", Google TV, Wi-Fi 192.168.28.206).
# Run from Git Bash on the laptop; each block is independent.
export MSYS_NO_PATHCONV=1
ADB="${ADB_EXE:-adb} -s ${TV:-192.168.28.206:5555}"

# 1. Animations (were 1.0 / 1.0 / unset)
$ADB shell settings put global window_animation_scale 1.0
$ADB shell settings put global transition_animation_scale 1.0
$ADB shell settings delete global animator_duration_scale

# 2. Screensaver (was Google TV Ambient mode)
$ADB shell pm enable com.google.android.apps.tv.dreamx
$ADB shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop

# 3. Launcher: back to Google TV (the setup wizard steals Home while Google TV is off, so it went too)
$ADB shell pm enable com.google.android.tungsten.setupwraith
$ADB shell pm enable com.google.android.apps.tv.launcherx
$ADB shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
# Optional: remove Lord of the Ping itself.
# $ADB shell pm uninstall com.lordoftheping.home
#
# setupwraith also holds com.android.systemupdateservice.SystemUpdateService. To check for a Google system
# update without giving Home back, enable it, check in Settings, then disable it again:
#   $ADB shell pm enable com.google.android.tungsten.setupwraith
#   $ADB shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
#
# Without adb, from the remote: Settings -> Apps -> See all apps -> Show system apps ->
#   "Google TV" -> Enable, then "Setup Wizard" -> Enable, then press Home.

# 4. Disabled apps (Xiaomi analytics and statistics, YouTube Music, Play Movies, Play Games)
for p in com.miui.tv.analytics com.xiaomi.statistic \
         com.google.android.youtube.tvmusic com.google.android.videos com.google.android.play.games; do
  $ADB shell pm enable "$p"
done

# Not reversible and not needed back: app caches were trimmed (pm trim-caches) and an empty managed profile
# (user 10 "new_user", created 2024-10-24, no profile owner, no apps, never started since) was removed.
