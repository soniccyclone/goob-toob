# goob-toob

[![builds.sr.ht status](https://builds.sr.ht/~nathanb/goob-toob.svg)](https://builds.sr.ht/~nathanb/goob-toob)

Self-hosted watch-together video app. Clojure server, ClojureScript browser,
ffmpeg → MPEG-DASH, one slime-green waterpark theme. You run it on your box,
upload your library, open a room, toss friends a link, everyone watches in
sync. You drive playback; viewers pick auto (best sync) or a locked resolution
and catch themselves up when their internet wobbles.

## Download

**[goob-toob-win-x64-setup.exe](https://nathanb.srht.site/goob-toob-releases/goob-toob-win-x64-setup.exe)** — Windows installer
&nbsp;&nbsp;·&nbsp;&nbsp;
**[goob-toob-linux-x86_64.AppImage](https://nathanb.srht.site/goob-toob-releases/goob-toob-linux-x86_64.AppImage)** — Linux

The desktop app bundles everything (JRE, ffmpeg, cloudflared): install it, pick
a video folder, and it hands you a public link to toss at your friends. No
terminal, no password — the admin console lives on loopback and the public
tunnel has no admin surface at all. Windows SmartScreen will squint because
there's no code signing: "More info → Run anyway".

Prefer a container? `make image && make run` below.

## Links

- **[Full docs: `README.org`](https://git.sr.ht/~nathanb/goob-toob/tree/master/item/README.org)** —
  quickstart, env vars, sharing, sync design, layout. sr.ht only renders
  markdown readmes, so the real docs live in org.
- **[Plans site](https://nathanb.srht.site/goob-toob-plans/)** — every plan
  rendered, newest first, with status stamps.
- **[Plans directory](https://git.sr.ht/~nathanb/goob-toob/tree/master/item/plans)** —
  the dated `.org` sources + plan-day html snapshots.
- **[Builds](https://builds.sr.ht/~nathanb/goob-toob)** — pushing master
  republishes the plans site.

## Quickstart

```sh
make image
STORAGE=/path/to/videos ADMIN_PASSWORD=*** make run
# open http://localhost:8080 → /admin#, log in, upload, make a room, share
```

To get the app publicly reachable for a party, `make tunnel` (cloudflared quick
tunnel, no account needed) — details in README.org.

## License

Copyright (C) 2026 Nathan Barlow — **AGPL-3.0-or-later** ([LICENSE](https://git.sr.ht/~nathanb/goob-toob/tree/master/item/LICENSE)).
Free to use, modify, and host; modified versions served over a network must
publish their source. Closed-source / commercial use = dual-license, [talk to
me](mailto:nathanjbarlow@gmail.com). Contributions are subject to the CLA in
[CONTRIBUTING.org](https://git.sr.ht/~nathanb/goob-toob/tree/master/item/CONTRIBUTING.org).
