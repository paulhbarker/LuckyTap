# Changelog

## [1.0.2](https://github.com/paulhbarker/LuckyTap/compare/v1.0.1...v1.0.2) (2026-05-08)


### Bug Fixes

* wire WiFi/WS defaults from CI secrets into BuildConfig ([5bda39a](https://github.com/paulhbarker/LuckyTap/commit/5bda39ab75807129ba0bdf8425803c56edd8eb89))

## [1.0.1](https://github.com/paulhbarker/LuckyTap/compare/v1.0.0...v1.0.1) (2026-05-08)


### Bug Fixes

* resolve three production-only crash/bug issues ([57ba7c1](https://github.com/paulhbarker/LuckyTap/commit/57ba7c1775e966d0478260749bcfe1fda16bbd73))

## 1.0.0 (2026-05-08)


### Features

* add NFC enabled state detection with prompt to enable ([2367c2f](https://github.com/paulhbarker/LuckyTap/commit/2367c2f6f67d4aed127026b4f86053b267ef5567))
* NFC auto-return from settings + app icon updates ([f3b4a01](https://github.com/paulhbarker/LuckyTap/commit/f3b4a0118baba4585b3c7b92b4fdc0cb26ec0667))
* redesign UI with Material 3 dark theme and connection status card ([7299ed2](https://github.com/paulhbarker/LuckyTap/commit/7299ed26e451233a295a51af4be2ba875e9aefc8))


### Bug Fixes

* add missing ACCESS_NETWORK_STATE to AndroidManifest ([271dc0a](https://github.com/paulhbarker/LuckyTap/commit/271dc0a71afb58f210b7f9f582376301816f114c))
* add top padding to main and settings screens ([014607f](https://github.com/paulhbarker/LuckyTap/commit/014607fdf11853bc0964acdbc946b5236a8a8c59))
* auto-reconnect WiFi when target network is lost ([9f201ae](https://github.com/paulhbarker/LuckyTap/commit/9f201ae35c072b1955323c781e62d39bd3cde41c))
* **compat:** maximize Android backward compatibility down to API 23 ([9253367](https://github.com/paulhbarker/LuckyTap/commit/9253367e36e5925d1ef7c5112e21219b72c2bee0))
* correct WebSocket endpoint path to /ws/lucky-tap ([b37495f](https://github.com/paulhbarker/LuckyTap/commit/b37495fcd867df75994893185265bffdea626ff2))
* correct WiFi connected state on API 29+ and add missing manifest permission ([443b323](https://github.com/paulhbarker/LuckyTap/commit/443b32377c359f3567d0978497f519559ce5e564))
* gate all WebSocket connections behind WiFi connectivity check ([65ac687](https://github.com/paulhbarker/LuckyTap/commit/65ac687dcd1771d53da5a2156ed4cb67bca5ce25))
* maintain CONNECTING state during WiFi retry cycle ([51aa3b7](https://github.com/paulhbarker/LuckyTap/commit/51aa3b79127f9b0cce438e7f4c3b250be987ae36))
* match Write NFC text field style with Settings fields ([648a9fc](https://github.com/paulhbarker/LuckyTap/commit/648a9fca0d7512771ddc7dfc099491e004a57197))
* only signal WiFi connected when actually on target network ([27c7e2b](https://github.com/paulhbarker/LuckyTap/commit/27c7e2bbf22e7eecb66abbb29847c468f0e88f27))
* resolve all Gradle/AGP deprecation warnings ([15ce75d](https://github.com/paulhbarker/LuckyTap/commit/15ce75d208905c4c528ed6ff832c3dd7cf2d9310))
* resolve WiFi connection failures on startup ([089c9da](https://github.com/paulhbarker/LuckyTap/commit/089c9daf63a587fc6be03747feb98d4ec53e8c4c))
* settings hint overlap, hide sections when disconnected, simplify status text ([c55eb30](https://github.com/paulhbarker/LuckyTap/commit/c55eb306341226cbd450a618611526e628da4590))
