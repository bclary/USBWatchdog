# USBWatchdog

USBWatchdog is an Android service which monitors the property
`usbwatchdog.heartbeat` and reboots the device if the value does not
change in a specified interval. USBWatchdog is intended to help
recover a lost USB connection from a host computer in the event the
host loses the ability to communicate with the device over USB.

# Android Support

Android 2.3+

## Building

The USBWatchdog repository contains an Android Studio 2.0 beta project
which can easily be built using [Android Studio 2.0
beta](http://tools.android.com/download/studio/beta).  Choosing the
`Build`->`Build APK` menu options from Android Studio will create a
debug version of the app in
`USBWatchdog/app/build/outputs/apk/app-debug.apk`.

## Installation

USBWatchdog requires a rooted device which either has the `su` command
available or which allows apps to be installed to `/system/app`.

Download a pre-built debug version of [USBWatchdog.apk](downloads/USBWatchdog.apk)
or build your own.

### User app installation

    adb install -r USBWatchdog.apk

### System app installation

Installing USBWatchdog as a system app varies depending on how your
device is rooted. The following will work on a device which can run
adbd as root and which can remount /system using adb remount.

    # Restart adbd as root.
    adb root
    # Make /system writable
    adb remount
    adb push USBWatchdog.apk /data/local/tmp/
    adb shell dd if=/data/local/USBWatchdog.apk of=/system/app/USBWatchdog.apk
    adb reboot

## Usage

USBWatchdog works in combination with a program running on the host
computer which periodically updates the `usbwatchdog.heartbeat`
property on the device. If `usbwatchdog.heartbeat` is not empty and has
not changed since the last time USBWatchdog polled the property's value,
then the device will log the time and reason to the file
`/data/data/com.mozilla.autophone.usbwatchdog/files/usbwatchdog.log`
and will reboot.

The host computer program should update the `usbwatchdog.heartbeat`
property more frequently than the USBWatchdog service polls the value.

USBWatchdog is started on the device via the command:

    adb shell am startservice \
                 -n com.mozilla.autophone.usbwatchdog/.USBService \
                 [--ei poll_interval <seconds>] [--esn debug]

        --ei poll_interval <seconds>

            specifies the time period between checks to see if the
            usbwatchdog.heartbeat property has changed. If the
            usbwatchdog.heartbeat property is not empty and has not
            changed within the interval, the device will reboot.

        --esn debug

            enables additional debugging output to logcat.
_