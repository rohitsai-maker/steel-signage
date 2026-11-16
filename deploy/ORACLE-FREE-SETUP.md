# Run Steel Signage free, 24/7, on Oracle Cloud

Oracle Cloud's **Always Free** tier gives you a small server that never expires, plus 10 TB of bandwidth a month, which is plenty for sending videos to many TVs. Setup takes about 15 minutes.

## 1. Create the Oracle account
1. Sign up at **https://signup.cloud.oracle.com**. A card is needed to verify your identity; Always Free resources are not charged.
2. Choose a **Home Region** close to your screens, for example *India South (Hyderabad)* or *India West (Mumbai)*. You can't change it later.

## 2. Create the free server (VM)
1. Console → **Compute → Instances → Create instance**.
2. **Image:** Canonical **Ubuntu 24.04**.
3. **Shape:** click *Change shape*:
   - **Ampere (ARM) → VM.Standard.A1.Flex**, 1–2 OCPU, 6–12 GB RAM (*Always Free eligible*), or
   - if that says "Out of capacity": **Specialty → VM.Standard.E2.1.Micro** (AMD, also Always Free). Steel Signage runs fine on it.
4. **Boot volume:** you can raise it to 100–150 GB (free up to 200 GB total). This is where your videos are stored.
5. **SSH keys:** choose *Generate a key pair* and **download the private key**.
6. Click **Create** and wait for the state to show **RUNNING**. Copy the **Public IP address**.

## 3. Open the web ports in Oracle's network
Console → **Networking → Virtual Cloud Networks** → your VCN → **Security Lists → Default Security List → Add Ingress Rules**:

| Source CIDR | IP Protocol | Destination Port |
|---|---|---|
| `0.0.0.0/0` | TCP | `80` |
| `0.0.0.0/0` | TCP | `443` |

## 4. Run the one-line installer
Connect to the server. Either click **Cloud Shell** (the `>_` icon in the Oracle console) or, on your PC:
```bash
ssh -i path/to/private.key ubuntu@YOUR-PUBLIC-IP
```
Then paste:
```bash
curl -fsSL https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/oracle-setup.sh | sudo ADMIN_PASSWORD=ChooseAStrongPassword bash
```
When it finishes, it prints your **admin panel link** and password. Open it in your browser, upload files and make a playlist. Then open the Steel Signage app on each TV and enter the same address.

## 5. (Recommended) Free HTTPS with your own name
1. Get a free subdomain at **https://www.duckdns.org**, for example `mysigns.duckdns.org`, and point it to your server's public IP.
2. Re-run the installer with the domain:
```bash
curl -fsSL https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/oracle-setup.sh | sudo DOMAIN=mysigns.duckdns.org bash
```
Caddy gets a free HTTPS certificate automatically. Your admin panel and the TV address become `https://mysigns.duckdns.org`.

## Keep it free and keep it running
- **Idle reclaim:** Oracle may reclaim Always Free VMs that stay almost idle for 7 days. To avoid this, upgrade the account to **Pay As You Go** (Billing → Upgrade). Always Free resources stay free, and idle reclaim doesn't apply to upgraded accounts. Set a **budget alert of ₹100** (Billing → Budgets) so you'd be warned before any charge.
- Stay on Always Free shapes, with 200 GB of disk or less in total.

## Everyday commands (on the server)
```bash
steel-signage-update                     # install the latest version from GitHub
steel-signage-backup                     # back up media + playlists + screens
sudo systemctl restart steel-signage     # restart
sudo journalctl -u steel-signage -f      # live logs
```
Re-running the installer is safe. It keeps your media, playlists, screens and password.
