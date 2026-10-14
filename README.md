# goob-toob

[![ci](https://github.com/soniccyclone/goob-toob/actions/workflows/ci.yml/badge.svg)](https://github.com/soniccyclone/goob-toob/actions/workflows/ci.yml)

Self-hosted watch-together video app. Clojure server, ClojureScript browser,
ffmpeg → MPEG-DASH, one slime-green waterpark theme. You run it on your box,
upload your library, open a room, toss friends a link, everyone watches in
sync. You drive playback; viewers pick auto (best sync) or a locked resolution
and catch themselves up when their internet wobbles.

## Download

**[goob-toob-win-x64-setup.exe](https://github.com/soniccyclone/goob-toob/releases/latest/download/goob-toob-win-x64-setup.exe)** — Windows installer
&nbsp;&nbsp;·&nbsp;&nbsp;
**[goob-toob-linux-x86_64.AppImage](https://github.com/soniccyclone/goob-toob/releases/latest/download/goob-toob-linux-x86_64.AppImage)** — Linux

The desktop app bundles everything (JRE, ffmpeg, cloudflared): install it, pick
a video folder, and it hands you a public link to toss at your friends. No
terminal, no password — the admin console lives on loopback and the public
tunnel has no admin surface at all.

Prefer a container? `make image && make run` below.

## Windows: install & first party

1. **Download**
   [goob-toob-win-x64-setup.exe](https://github.com/soniccyclone/goob-toob/releases/latest/download/goob-toob-win-x64-setup.exe)
   (~250 MB — it carries its own JRE, ffmpeg, and cloudflared, so there is
   nothing else to install).
2. **Run it.** SmartScreen will say *"Windows protected your PC"* because the
   installer isn't code-signed: click **"More info" → "Run anyway"**. It
   installs per-user (no admin prompt) and adds a Start-menu shortcut.
3. **First launch** asks *"Where should goob-toob keep your videos?"* — pick
   (or create) the folder your library lives in. Uploads, the database, and
   the generated streams all live under that one folder.
4. The **admin console** window opens. The *sharing* card at the top shows
   your public link once the tunnel dials (a few seconds) — hit **copy link**.
   Friends just open that link in any browser; they install nothing.
5. **Upload** videos by dragging files onto the drop zone (or click it to
   pick). Each file shows progress; wait for its status to reach **ready** —
   ffmpeg builds a multi-bitrate stream per file, one at a time, so transcode
   before the party.
6. **Make room** on a ready video: the room link lands on your clipboard,
   already dressed as your public link. Toss it to friends.
7. **Open the room link yourself to host.** Play, pause, and seek drive
   everyone in the room; press the video's native play button to start
   playback. Tick *co-hosts can drive* in the room list if friends should be
   able to steer too.

Good to know:

- The public link is a Cloudflare **quick tunnel**: it's ephemeral, so every
  app launch gets a new URL — re-copy it from the sharing card after a
  restart.
- Nothing is exposed on your network: the tunnel dials *out*, and the admin
  console only listens on loopback — that's why there's no password.
- Trouble? The log is `%APPDATA%\goob-toob-desktop\server.log`. The storage
  folder can be changed anytime with the **change** button on the sharing
  card (the server restarts against the new folder).
- Uninstall from *Settings → Apps* like anything else.

## Linux

Download the
[AppImage](https://github.com/soniccyclone/goob-toob/releases/latest/download/goob-toob-linux-x86_64.AppImage),
`chmod +x` it, and run it — same first-run folder picker and sharing card as
Windows, steps 3–7 above apply.

## Links

- **[Full docs: `README.org`](https://github.com/soniccyclone/goob-toob/blob/main/README.org)** —
  quickstart, env vars, sharing, sync design, layout. The markdown you're
  reading is the short version; the org file is the real manual.
- **[Plans site](https://nathanb.srht.site/goob-toob-plans/)** — every plan
  rendered, newest first, with status stamps.
- **[Plans directory](https://github.com/soniccyclone/goob-toob/tree/main/plans)** —
  the dated `.org` sources + plan-day html snapshots.
- **[Releases](https://github.com/soniccyclone/goob-toob/releases)** —
  desktop installers, newest first.
- **[Actions](https://github.com/soniccyclone/goob-toob/actions)** — CI on
  every push; pushing a `v*` tag cuts a release.

## Quickstart

```sh
make image
STORAGE=/path/to/videos ADMIN_PASSWORD=*** make run
# open http://localhost:8080 → /admin#, log in, upload, make a room, share
```

To get the app publicly reachable for a party, `make tunnel` (cloudflared quick
tunnel, no account needed) — details in README.org.

## License

Copyright (C) 2026 Nathan Barlow — **AGPL-3.0-or-later** ([LICENSE](https://github.com/soniccyclone/goob-toob/blob/main/LICENSE)).
Free to use, modify, and host; modified versions served over a network must
publish their source. Closed-source / commercial use = dual-license, [talk to
me](mailto:nathanjbarlow@gmail.com). Contributions are subject to the CLA in
[CONTRIBUTING.org](https://github.com/soniccyclone/goob-toob/blob/main/CONTRIBUTING.org).
