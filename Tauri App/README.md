

#  THE WEBSIE

https://zerolauncher.github.io/page/

<p align="center">
  <img src="assets/banner.png" alt="Zero Launcher Banner">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Windows%20%7C%20Linux-2ea44f?style=for-the-badge" alt="Platform">
  <img src="https://img.shields.io/badge/Built%20With-Tauri%20%26%20Rust-24c8ce?style=for-the-badge" alt="Built With">
  <img src="https://img.shields.io/badge/Status-In%20Development-8b5cf6?style=for-the-badge" alt="Status">
</p>

<p align="center">
A modern, lightweight, and highly customizable Minecraft launcher built entirely with rust (Tauri), designed for both Windows and Linux.
</p>

---

> [!NOTE]
>
> **Zero Launcher** has switched to Rust & Tauri!
>
>  This Project Is still in beta expect Bugs, Currently best Performance on windows because Windows WebView2 Is more Optimized then Linux WebKitGTK this may change in the future 
>
> .


**IF YOU DON'T TRUST THE LAUNCHER** You can scan it at [VirusTotal](https://www.virustotal.com/gui/) or you can simply don't use it

## Crash & freeze reports (Discord webhook)

Panics, native crashes, unclean exits (OOM/kill/power loss) and UI freezes are
reported to a Discord webhook with environment details and a scrubbed log.

* Set the webhook in **Settings → Privacy & Developer → Crash Reports**, or bake a
  default into a build: `ZL_CRASH_WEBHOOK='https://discord.com/api/webhooks/…' ./build.sh --release`
  (never commit the URL).
* Crashes are uploaded on the *next* launcher start (a dying process can't safely
  use the network); freezes are uploaded immediately (max 3/session, 5 min apart).
* Uploaded: crash report + backtrace, launcher log of that run, OS/CPU/RAM/GPU,
  WebView version, session type, packaging, filtered env vars, launcher state and
  non-sensitive settings. Home dir, username, account names/UUIDs, tokens, JWTs,
  e-mails and public IPs are stripped first. A `<report>.sent` file prevents re-uploads.
* Anyone who can read the binary can extract a compiled-in webhook and spam or delete it.
  For a public release, point the webhook at a small relay you control instead.
