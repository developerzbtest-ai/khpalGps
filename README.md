<p align="center"><img src="docs/logo.png" width="160" alt="KHPAL GPS logo"></p>

# KHPAL GPS

KHPAL GPS is an Xposed/LSPosed (Vector) module for Android. Pick a spot on the map and the module delivers it to the apps you select, with natural live variation.

Developer: **MK_MANDOKHAIL**  ·  Version **1.0.1**  ·  Package `com.khpalgps.zb`

## Features
- Map picker (free OpenStreetMap / Esri / OpenTopoMap tiles in the *foss* build, Google Maps in the *full* build) with zoom + / − buttons
- **The location provider follows Android**: the injected location keeps the provider Android really used (gps, network, fused), never forced to GPS
- Separate accuracy for the GPS, network and fused providers, each with a "randomize ±" range
- Altitude, speed (m/s), MSL altitude, horizontal and vertical accuracy
- Optional random drift inside a radius, with a custom interval and a randomize-interval (hours / minutes / seconds)
- Favorites, search, joystick, dark theme

## Build
Push this project to GitHub; the workflow in `.github/workflows/build.yml` builds the *foss* and *full* APKs (Actions tab → artifact **KHPAL-GPS-apks**).

## Use responsibly
Intended for app developers, testers and privacy-minded users. Respect local laws and the rules of the apps you use.

## Credits and licence
KHPAL GPS is a **modified version** of GPS Setter by [@jqssun](https://github.com/jqssun/android-gps-setter), which itself is based on GPS Setter by [@Android1500](https://github.com/Android1500/GPS-Setter). Licensed under the **GNU General Public License v3.0** (see `LICENSE`). The original copyright and licence notices are kept in `NOTICE`, as the GPL requires.
