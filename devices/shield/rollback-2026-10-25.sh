#!/usr/bin/env bash
# Rollback of the 2026-10-25 tweaks on the NVIDIA SHIELD TV Pro 2019 (mdarcy, Android 11, Wi-Fi Home 192.168.1.103;
# shows on the Raz TV's HDMI 3). Run from Git Bash on the laptop. Each block is independent.
# Before-state: settings-before-2026-10-25.txt and disabled-before-2026-10-25.txt next to this script.
export MSYS_NO_PATHCONV=1
ADB="/c/platform-tools/adb.exe -s ${SHIELD:-192.168.1.103:5555}"

# 1. Animations (were window 1.0, transition 1.0, animator unset)
$ADB shell settings put global window_animation_scale 1.0
$ADB shell settings put global transition_animation_scale 1.0
$ADB shell settings delete global animator_duration_scale

# 2. NVIDIA data collection (was on)
$ADB shell settings put system nvidia_collect_data_enabled 1

# 3. Shield SMB server (shares the Shield's own storage; Kodi reads from the Giga router instead) and NVIDIA Share
#    (screenshots, recording, broadcasting)
$ADB shell pm enable com.nvidia.shield.smbserver
$ADB shell pm enable com.nvidia.osc

# 4. Launcher: back to Android TV Home (Lord of the Ping was put on trial 10-25)
$ADB shell pm enable com.google.android.tvrecommendations
$ADB shell pm enable com.google.android.tvlauncher
$ADB shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
# Optional: remove Lord of the Ping itself.
# $ADB shell pm uninstall com.lordoftheping.home

# Not reversible from here:
# - Plex Media Server (com.plexapp.mediaserver.smb) was uninstalled at the user's request; reinstall from Google Play.
# - Netflix (com.netflix.ninja) was uninstalled 2026-10-28 at the user's request (unused, kept a background
#   service); reinstall from Google Play. Without it the remote's Netflix button has nothing to open.
# - App caches were trimmed (about 4.7 GB); apps rebuild them as needed.
