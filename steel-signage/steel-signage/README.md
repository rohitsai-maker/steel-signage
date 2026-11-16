# 📺 Steel Signage — open-source digital signage for Android TV

Upload images and videos in a web admin panel, put them in playlists, and play them on any Android TV, TV box, Fire TV stick or browser. Change content from anywhere and every screen updates within about 15 seconds.

```
 ┌──────────────────────┐   upload / playlists / screens   ┌───────────────┐
 │  Admin panel (web)   │ ───────────────────────────────▶ │  Steel Signage     │
 └──────────────────────┘                                  │  server       │
 ┌──────────────────────┐   pair code · poll · download    │  (Node.js)    │
 │  Android TV app      │ ◀──────────────────────────────▶ │               │
 │  /player in browser  │                                  └───────────────┘
 └──────────────────────┘
```

## Features (v0.1)

- **Upload files**: drag-and-drop images (jpg, png, gif, bmp, webp) and videos (mp4, webm, mkv, mov), with progress bars
- **Playlists**: reorder items, set how long each image shows (videos play to the end), assign one playlist to many screens
- **Screen pairing**: the TV shows a 6-digit code, which you type into the admin panel
- **Screen status**: online/offline, last seen, device model, what's playing now
- **Offline playback**: files are downloaded to the TV and keep playing if the internet drops, even after a reboot
- **Orientation**: landscape, portrait, or flipped, set per screen from the panel
- **Auto-start on boot** and kiosk behaviour (BACK doesn't exit the app)
- **Web player** at `/player` for testing, or for smart TVs that have a browser
- **Server with zero dependencies**: runs on plain Node.js 18+, with no npm install and no database to set up

## 1. Run the server

```bash
cd server
ADMIN_PASSWORD=change-me node server.js
# Admin panel → http://<your-ip>:8080/   (user: admin)
```

Environment variables: `PORT` (8080), `ADMIN_USER` (admin), `ADMIN_PASSWORD` (admin, so change it), `DATA_DIR` (./data), `MAX_UPLOAD_MB` (2048).

**Docker:**
```bash
cd server && docker build -t steel-signage . 
docker run -d -p 8080:8080 -e ADMIN_PASSWORD=change-me -v steel-signage-data:/data steel-signage
```

**On AWS EC2 / any VPS:** run the server behind nginx or Caddy with HTTPS, and keep `DATA_DIR` on persistent disk. Everything (database + media) lives in that one folder, so backups are just copying it.

## 2. Install the Android TV app

**Option A, GitHub Actions (no Android Studio needed):** push this repo to GitHub, open **Actions → Build Android APK**, and download the APK from the run's artifacts.

**Option B, Android Studio:** open the `android/` folder, let Gradle sync, then *Build → Build APK(s)*.

**Put it on the TV:**
- `adb connect <tv-ip>` then `adb install app-release.apk` (turn on Developer options → USB/Network debugging on the TV first), or
- copy the APK to a USB stick and open it with a file manager app on the TV, or
- use the "Downloader" app on Fire TV / Android TV.

## 3. Pair and play

1. Open **Steel Signage** on the TV, type the server address (e.g. `http://192.168.1.10:8080`) with the remote, and press **Save & start**.
2. The TV shows a **6-digit code**.
3. In the admin panel: **Media** → upload files → **Playlists** → New → click files to add → **Save**.
4. **Screens** → enter the code, a name and the playlist → **Pair screen**. Playback starts.

Remote control: press **MENU** (or **BACK** 5 times within 3 s) to get back to the setup screen.
For auto-start after power-on on Android 10+, press **Allow auto-start on boot** on the setup screen and enable *Display over other apps*.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/admin/media` | Basic | Upload (raw body, `X-Filename` header) |
| GET/PUT/DELETE | `/api/admin/media[/:id]` | Basic | List / rename / delete media |
| GET/POST/PUT/DELETE | `/api/admin/playlists[/:id]` | Basic | Manage playlists `{name, defaultDuration, items:[{mediaId, duration}]}` |
| GET | `/api/admin/screens` | Basic | Screens with online status |
| POST | `/api/admin/screens/pair` | Basic | `{code, name, playlistId}` |
| PUT/DELETE | `/api/admin/screens/:id` | Basic | Update `{name, playlistId, orientation}` / unpair |
| POST | `/api/player/register` | device | `{deviceId, info}` → pair code or content |
| GET | `/api/player/:deviceId/content` | device | Current playlist + version hash |
| GET | `/media/:file` | public | Media file (supports HTTP Range) |

## Roadmap (OptiSigns-style features)

- [ ] Schedules (dayparting, date ranges, events)
- [ ] Split-screen zones and layouts
- [ ] Apps: web page/URL, YouTube, RSS ticker, clock, weather, Google Slides
- [ ] PDF and PowerPoint (converted to images on upload)
- [ ] Instant push via WebSocket, plus remote screenshot and restart
- [ ] Multiple users and roles, workspaces
- [ ] Proof-of-play reports, audit log
- [ ] TV power on/off schedule, daily auto-restart
- [ ] Templates and a design editor
- [ ] Video walls, background music, kiosk/touch interactivity

## License

MIT
