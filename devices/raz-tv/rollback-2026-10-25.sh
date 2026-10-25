#!/usr/bin/env bash
# Rollback of the 2026-10-25 tweaks on the Raz TV ("Квант" TV_PCB_KV9632, MediaTek m7332, AOSP Android 11 TV with
# OneWay software, Wi-Fi Home 192.168.1.131; a SHIELD sits on HDMI 3). Run from Git Bash on the laptop.
# Each block is independent. Before-state of all settings: settings-before-2026-10-25.txt next to this script.
export MSYS_NO_PATHCONV=1
ADB="/c/platform-tools/adb.exe -s ${TV:-192.168.1.131:5555}"

# 1. Animations (were window 1.0, transition 1.0, animator unset)
$ADB shell settings put global window_animation_scale 1.0
$ADB shell settings put global transition_animation_scale 1.0
$ADB shell settings delete global animator_duration_scale

# 2. Disabled apps: Marusia voice service, EShare/iMirror/EDLNA mirroring servers, preinstalled 24ТВ, KION and the
#    OneTube YouTube wrapper, and Okko, PREMIER, ivi, Yandex Browser (installed from RuStore, hidden from
#    `pm list packages` by this firmware).
for p in ru.mail.channel.voiceservice com.ecloud.eshare.server com.ecloud.eairplay com.ecloud.emedia \
         ag.tv.a24h ru.mts.mtstv com.oneway.tv.youtube tv.okko.androidtv one.premier.rustoretv ru.ivi.client \
         com.yandex.browser.tv; do
  $ADB shell pm enable "$p"
done

# 3. Launcher: back to "One TV home". Lord of the Ping also switched the TV to the SHIELD's HDMI 3 on wake-up,
#    which the firmware itself doesn't do (it ignores the SHIELD's CEC <Active Source> after waking).
$ADB shell pm enable com.oneway.tv.home
$ADB shell cmd package set-home-activity com.oneway.tv.home/.HomeActivity
# Optional: remove Lord of the Ping itself.
# $ADB shell pm uninstall com.lordoftheping.home
