#!/usr/bin/env bash
# Rollback of the 2026-10-25 TCL TV ("Office TV", Wi-Fi Home 192.168.1.67) tweaks. Run from Git Bash on the laptop.
# Each block is independent; run only the part you want to undo.
export MSYS_NO_PATHCONV=1
ADB="/c/platform-tools/adb.exe -s ${TV:-192.168.1.67:5555}"

# 1. Animations (were 1.0 / 1.0 / unset)
$ADB shell settings put global window_animation_scale 1.0
$ADB shell settings put global transition_animation_scale 1.0
$ADB shell settings delete global animator_duration_scale

# 2. Screensaver (was Google TV Ambient mode)
$ADB shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop

# 4. Launcher: back to Google TV. The setup wizard is the guard that steals Home while Google TV is off,
#    so it was disabled together with it.
$ADB shell pm enable com.google.android.tungsten.setupwraith
$ADB shell pm enable com.google.android.apps.tv.launcherx
$ADB shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
# Optional: remove Lord of the Ping itself.
# $ADB shell pm uninstall com.lordoftheping.home
#
# Without adb, from the remote: Settings -> Apps -> See all apps -> Show system apps ->
#   "Google TV" -> Enable, then "Setup Wizard" -> Enable, then press Home.

# 6. Gear-key accessibility service of Lord of the Ping (was: none, accessibility off) and TCL logkit
$ADB shell settings delete secure enabled_accessibility_services
$ADB shell settings put secure accessibility_enabled 0
$ADB shell pm enable com.tcl.logkit

# 5. Keyboard: back to the TCL keyboard (Gboard was made default 10-25 for voice typing with the remote's
#    Google button; it stays enabled as a second keyboard).
$ADB shell ime set com.tcl.inputmethod.international/.T_IME

# 3. Disabled apps (Netflix and Prime Video were already disabled before)
for p in tv.samba.ssm.ui com.tcl.esticker com.tcl.exhibit com.tcl.hotelmenu com.tcl.ocean.instructions \
         com.tcl.repairguide com.tcl.smartalexa com.tcl.messagebox com.tcl.gamebar com.tcl.hearaid \
         com.tcl.channelplus com.google.android.play.games \
         com.realtek.airplay2 com.tcl.airplay2 com.tcl.miracast com.google.android.apps.tv.dreamx \
         com.google.android.youtube.tvmusic com.google.android.videos com.tcl.waterfall.overseas \
         com.tcl.tv.tclhome_passive com.tcl.usercenter com.tcl.magiconnectfree; do
  $ADB shell pm enable "$p"
done
