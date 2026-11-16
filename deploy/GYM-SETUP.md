# Steel Signage for one location (gym, shop, office): free, managed from anywhere

```
 Reception PC ──Wi-Fi──► TVs        fast and free; keeps playing even if the internet is down
      │
      └── Cloudflare Tunnel (free) ──► you, from anywhere: https://signs.yourgym.in
```

**Cost:** ₹0 for the software. The only optional cost is a domain name (₹100–900/year). If your gym already has a website domain, you can use that for free.

---

## Part A: Install on the reception PC (10 minutes)

You need any Windows 10/11 PC or laptop that stays on and plugged in. An old one is fine.

1. Click **Start**, type **PowerShell**, right-click **Windows PowerShell** and choose **Run as administrator**.
2. Paste this, change the password, and press Enter:
   ```powershell
   $env:SS_PASSWORD='ChooseAStrongPassword'; irm https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/windows-setup.ps1 | iex
   ```
3. When it finishes, it shows:
   - **Admin panel:** `http://localhost:8080`
   - **Address for the TVs:** for example `http://192.168.1.20:8080`
4. **Reserve that IP:** in your Wi-Fi router, find "DHCP reservation" or "Address reservation" and fix this PC's IP, so the TV address never changes.

The server starts by itself whenever the PC turns on, and the installer turns off sleep while the PC is plugged in.

## Part B: Set up the TVs

1. On each TV, install the app: **https://github.com/rohitsai-maker/steel-signage/releases/latest/download/SteelSignage-TV.apk**. The easiest way is the free **Downloader** app.
2. Open **Steel Signage**, enter the TV address from Part A (e.g. `http://192.168.1.20:8080`), and press **Save & start**.
3. The TV shows a **6-digit code**. In the admin panel, go to **Screens**, enter the code, and pick a playlist.

## Part C: Manage from anywhere (free Cloudflare Tunnel)

1. **Domain:** add your domain to a free Cloudflare account (cloudflare.com → *Add a site*), then change the domain's nameservers to the two Cloudflare gives you. You can buy a cheap `.in` domain from any registrar, or buy one directly in Cloudflare.
2. Open **Cloudflare dashboard → Zero Trust → Networks → Tunnels → Create a tunnel** → choose **Cloudflared** → name it `gym`.
3. On the "Install connector" screen, choose **Windows** and copy **only the long token**, the text after `service install`, which starts with `eyJ`.
4. Next, add a **Public hostname**:
   - Subdomain: `signs` · Domain: `yourgym.in`
   - Service type: **HTTP** · URL: `localhost:8080`
   - Save.
5. On the reception PC, open PowerShell as administrator again and run:
   ```powershell
   $env:SS_TUNNEL_TOKEN='eyJh...paste-token...'; irm https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/windows-setup.ps1 | iex
   ```
6. Done. Open **https://signs.yourgym.in** from your phone or laptop, anywhere, and log in as `admin` with your password.

> TVs can stay on the local address (fastest). You only need the `https://signs…` address to manage content remotely.

---

## Day-to-day
- **Change content from anywhere:** open `https://signs.yourgym.in`, upload, and edit the playlist. TVs update within about 15 seconds.
- **Update Steel Signage:** run the Part A command again. Your media, playlists and screens are kept.
- **Backup:** copy the folder `C:\SteelSignage\data`.
- **Logs:** `C:\SteelSignage\server.log`.

## No PC at the gym?
Use the free Oracle Cloud server instead: see [ORACLE-FREE-SETUP.md](ORACLE-FREE-SETUP.md). The TVs then download from the cloud over the gym's internet.
