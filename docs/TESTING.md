# Test checklist

Use the [installation guide](INSTALL.md). With the car parked, verify wired and wireless connection, picture, touch and music. Test disconnect/reconnect, then settings Apply/Cancel. Save a diagnostic report after reproducing an issue.

## Diagnostic export without a picker

On an Android 9 emulator or head unit without a document picker, open **Settings → Diagnostics → Save diagnostic report**. Confirm that no picker is required and that the success dialog shows a TXT file under `Android/data/<package>/files/diagnostic-reports/`. Read that file and verify the app/device information and UTF-8 text. Use **View** and **Share** from the confirmation. Export twice and confirm that the reports have distinct file names and the earlier file is not overwritten. On Android 10+, normal exports should still use `Downloads/DiPlay`; **Choose save location** should still open a working picker, and cancelling it should not export anything. If external storage is unavailable, confirm that the private in-app fallback can still be viewed and shared. Do not disable system components on a car to simulate the missing-picker case; use an emulator for that simulation.

For channel memory, connect until authenticated CarPlay renders, disconnect and reconnect without changing the car's Wi-Fi association. Look for `remembered saved` followed by `remembered first`. Report absent events; creating a hotspot alone is insufficient.

Include head-unit model, DiLink/Android, iPhone/iOS, wired/wireless, app version and exact steps. Do not post credentials or unreviewed personal information. See [compatibility](COMPATIBILITY.md) for remaining limitations.

## Preferred Wi-Fi Direct channel

- In **Settings → Connection setup → Wi-Fi Direct**, confirm **Preferred channel: Auto** on a fresh install. Select channel 149 and Cancel; Auto must remain selected. Select 149 and Save, reopen the chooser and restart the app to confirm it stays saved.
- Disconnect/reconnect after saving. Check `channel preference=149 frequencyMHz=5745`, `create mode=PREFERRED_CHANNEL`, and `requestedMHz=5745 actualMHz=5745 matched=true`. An unsupported channel or a different actual channel must report an error instead of silently falling back. Select Auto to restore automatic startup.
- Compare Auto and manual choices with the car already joined to Wi-Fi. A manual choice must override station alignment and any remembered automatic channel. Successful manual sessions must not replace the remembered automatic configuration.
- Switch to built-in hotspot and USB. The channel chooser must be hidden for built-in hotspot, and neither connection may apply the Wi-Fi Direct preference. Returning to Wi-Fi Direct must restore the saved choice. Saving a channel during a connection must leave that session running and apply the change to the next connection.

## Wireless and USB car data

- On wireless, with **Report location to iPhone** on, confirm the Bluetooth bootstrap identifies with `location=false vehicleStatus=false`, then the Wi-Fi tunnel receives its own `start-location-information` before its first `location-information`. There must be no location output on Bluetooth and no unsolicited continuation from it.
- On USB, start a fresh wired session rather than plugging into an already active wireless session. Confirm the USB iAP2 link receives StartLocationInformation and carries all location updates itself.
- With **Car battery for the iPhone** on, confirm on wireless that the Bluetooth bootstrap does not advertise Vehicle Status and that the Wi-Fi tunnel receives its own `0xa100 start-vehicle-status` before sending `0xa101 vehicle-status`. On USB, the single wired iAP2 link must receive `0xa100` and send `0xa101`. A missing or stale battery reading must leave Vehicle Status undeclared rather than sending invented values.

## Video while parked

- Use a plain HTTPS MP4 or HLS item that supports AirPlay, such as one sent from Safari. DRM-protected services and apps that disable AirPlay are not acceptance tests.
- Test fresh wireless and fresh USB sessions separately. Confirm `/info videoInCar=true`, SETUP negotiates `videoPlayback`, the event channel becomes ready, and the latest P-state reports `delivery=SENT` even if it was first `QUEUED`.
- Confirm the video settings stream and remote-control stream are accepted, `requestUI videoplayback:` opens the player, and the player log reports a validated internet network before loading the URL.
- Shift out of P and confirm availability becomes false and the car player closes immediately. Disable ADB or make the gear unreadable and confirm the same fail-closed behavior.

## Rotation during reconnect

On an Android device that supports screen rotation, connect until CarPlay renders, then rotate from landscape to portrait and back while the connection is rebuilding. Repeat in both directions, including several quick rotations and a 180-degree turn. Let the device settle after the last rotation and check that the CarPlay picture has the correct aspect ratio and that touch targets match the displayed controls.

In the diagnostic report, the next `Starting CarPlay controller at` and `Display request` must use the latest settled dimensions, including a `Display updated while handshake is reset` event that arrived during teardown. A queued size change must settle before startup; cancelling it by returning to the accepted size must still resume the connection. On a BYD head unit, also open and close the camera window to confirm that a shrink/restore within the original window keeps the existing CarPlay session.

## Location reporting

With the car parked, open **Settings → Location → Report location to iPhone**.

- On a fresh installation, the switch is off. Enabling it requests precise location if needed; denying the request or granting only approximate location leaves it off.
- Grant precise location, enable the switch, then reopen Settings to confirm the saved state. With no connection running, the setting applies to the next connection.
- During wired and wireless CarPlay, enabling or disabling the switch reconnects the session. When enabled and requested by the iPhone, check for `start-location-information` and `location-information` in the OsnPlay diagnostics; on wireless, also verify that reporting continues after the Bluetooth-to-Wi-Fi handoff.
- Disable the switch and confirm the next session does not advertise location reporting. These checks verify the accessory reporting path; they do not establish which inputs iOS uses in each fused location result.

## Advanced vehicle data

- Expand **Settings → Location → Advanced vehicle data**. Confirm a fresh install uses **Default mode · verified on DiLink 5.0 head units** and shows the battery, wheel-speed and parked-video switches without a field probe.
- In Default mode, tap **Check ADB access** and record the battery, speed and gear it shows. With CarPlay connected, turn on a switch whose data cannot be read: CarPlay must stay connected and the page must show what cannot be read.
- Select **Legacy head-unit detection · tested on controller 13 / DiLink 3.0**. Approve the key if the car asks; the same action must continue into the read-only field probe. With “Always allow” ticked, the page must not say the car allowed OsnPlay only once. A failed probe must leave Default mode selected.
- Reopen Settings, restart OsnPlay, change gear and reconnect CarPlay. The successful probe, resolved fields and enabled battery/wheel-speed/video switches must remain saved without another tap, even when the current firmware metadata differs.
- Switch back to Default mode and confirm the saved legacy probe remains available when Legacy mode is selected again.
- Turn ADB off temporarily. Saved functions and switches must remain visible; turning ADB back on allows automatic validation. Two READY-but-unreadable validations trigger one automatic re-probe, while an incomplete re-probe preserves the previous snapshot and shows manual retry.
- Press the first probe, authorization and retry controls after scrolling down the page. Progress and results must remain at the same scroll position rather than jumping to the top.
- Scroll down Settings, open CarPlay, then return to Settings (Back to OsnPlay or the three-finger gesture). The page must keep its scroll position.
- When the BYD navigation card is available, confirm **Dashboard song** exists there exactly once and does not appear in Advanced vehicle data. Without that card, it must appear once under Advanced vehicle data, and turning it on must show the CarPlay song on the dashboard.

## Hotspot and vehicle-settings interaction

- On a supported BYD unit, choose the built-in car hotspot. Automatic hotspot startup stays off on a fresh installation. Enable it explicitly and approve the ADB prompt; the setting saves only after OsnPlay confirms its own required permissions. Denial must leave it off. Choosing Wi-Fi Direct hides the hotspot card and preserves its saved preference.
- Expand Advanced vehicle data. Battery, wheel-speed and parked-video switches must appear only in that section, with unavailable legacy fields hidden. The hotspot card must not provide duplicate switches that bypass the selected mode.
- Start a user vehicle check or probe, then try the hotspot switch before it finishes. A second authorization flow must not start. After the vehicle operation finishes, the hotspot switch becomes usable again.
- Start hotspot authorization while Advanced vehicle data is expanded. Mode and vehicle choices must stay disabled until it completes; an automatic saved-field validation must resume afterwards without another authorization prompt.
- During a pending battery preflight, let the hotspot eligibility check finish and redraw Settings. A valid vehicle result must still apply the requested reconnect once; an unreadable result or ADB failure must keep the existing connection.
