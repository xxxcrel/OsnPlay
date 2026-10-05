# OsnPlay icons

`lynkco-logo.png` is the Lynk & Co wordmark fetched from the header of the
official website, https://www.lynkco.com.cn, on 2026-10-04:

https://dm30webimages.lynkco.com.cn/LynkCoPortal/media/0b74de4359d34f87a728d7d9f17971ae/2024-05-31/logo.png

It is used for the requested CarPlay "return to the car" icon. Lynk & Co owns
the mark. `osnplay-launcher-preview.png` is the rectangular car app-list design,
containing the original green CarPlay icon from Apple's official CarPlay page:

https://www.apple.com/v/ios/carplay/n/images/overview/carplay_icon__ecpi2mie6imq_large_2x.jpg

`apple-carplay-icon.jpg` was downloaded on 2026-10-04. Apple owns the CarPlay mark.
From 1.1.1, all rectangular padding extends the source icon's green gradient;
the launcher icons/banner are fully opaque, with no dark border or transparent corners.
The PNG resources can be regenerated with Pillow and
`scripts/generate-osnplay-icons.py --logo asset/osnplay/lynkco-logo.png --carplay-icon asset/osnplay/apple-carplay-icon.jpg`.
