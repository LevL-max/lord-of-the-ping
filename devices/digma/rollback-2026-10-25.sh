#!/usr/bin/env bash
# Rollback of the 2026-10-25 tweaks on the Digma TV (Skyworth SWTV-22TGS-4K, Wi-Fi Home 192.168.1.51).
# Run from Git Bash on the laptop; each block is independent.
export MSYS_NO_PATHCONV=1
ADB="/c/platform-tools/adb.exe -s ${TV:-192.168.1.51:5555}"

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
# Without adb, from the remote: Settings -> Apps -> See all apps -> Show system apps ->
#   "Google TV" -> Enable, then "Setup Wizard" -> Enable, then press Home.

# 6. Gear-key accessibility service of Lord of the Ping (was: no accessibility services, accessibility off)
$ADB shell settings delete secure enabled_accessibility_services
$ADB shell settings put secure accessibility_enabled 0

# 5. Gboard languages: "Use system languages" was ON (Russian + Alphabet); English (US) was added with it OFF.
#    Undo from the remote: Settings -> Keyboard -> Gboard settings -> Languages -> "Use system languages" ON.

# 4. Disabled apps (Netflix, TalkBack, Sky Market and Smartdevice MyApp were already disabled before)
for p in com.smartdevice.dtv.icast com.smartdevice.recommendation com.smartdevice.recordactivation \
         com.smartdevice.dailymanage com.smartdevice.aftersale com.security.SwaiotTV com.Smartdevice.emanual \
         com.coocaa.demonow.lightining fusion.android.tv.demo ru.link.rulaunch \
         com.google.android.youtube.tvmusic com.google.android.videos com.google.android.play.games \
         com.google.android.apps.nbu.smartconnect.tv com.amazon.amazonvideo.livingroom; do
  $ADB shell pm enable "$p"
done
