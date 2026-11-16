#!/usr/bin/env bash
# Steel Signage — one-command server setup for Oracle Cloud Always Free (Ubuntu 22.04 / 24.04, ARM or AMD).
# Also works on any Ubuntu/Debian VPS or an always-on Ubuntu PC.
#
# Usage (on the server):
#   curl -fsSL https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/oracle-setup.sh | sudo bash
#
# Optional settings (put them right after "sudo"):
#   ADMIN_PASSWORD=yourpass   admin panel password (default: random, printed at the end)
#   DOMAIN=signs.example.com  your domain / DuckDNS name -> automatic free HTTPS
#   PORT=8080                 internal Node port (default 8080)
# Example:
#   curl -fsSL https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/oracle-setup.sh | sudo DOMAIN=mysigns.duckdns.org ADMIN_PASSWORD=Secret123 bash
#
# Re-running the script is safe: it updates the code and keeps your media, playlists and password.

set -euo pipefail

REPO="https://github.com/rohitsai-maker/steel-signage.git"
APP_DIR="/opt/steel-signage"
DATA_DIR="/var/lib/steel-signage"
ENV_FILE="/etc/steel-signage.env"
APP_USER="steelsignage"
PORT="${PORT:-8080}"
DOMAIN="${DOMAIN:-}"

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run with sudo (see usage at the top of this script)."
command -v apt-get >/dev/null || die "This script supports Ubuntu/Debian only."

export DEBIAN_FRONTEND=noninteractive

say "Installing system packages"
apt-get update -y
apt-get install -y curl git ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https iptables-persistent netfilter-persistent

say "Installing Node.js 20"
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 18 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
node --version

say "Creating service user and folders"
id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$DATA_DIR"
chown -R "$APP_USER:$APP_USER" "$DATA_DIR"

say "Downloading Steel Signage"
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" fetch --depth 1 origin main
  git -C "$APP_DIR" reset --hard origin/main
else
  rm -rf "$APP_DIR"
  git clone --depth 1 "$REPO" "$APP_DIR"
fi

say "Writing configuration"
if [ -f "$ENV_FILE" ] && [ -z "${ADMIN_PASSWORD:-}" ]; then
  echo "Keeping existing settings in $ENV_FILE"
  # shellcheck disable=SC1090
  ADMIN_PASSWORD="$(grep '^ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
else
  ADMIN_PASSWORD="${ADMIN_PASSWORD:-$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 14)}"
fi
cat > "$ENV_FILE" <<EOF
PORT=$PORT
ADMIN_USER=admin
ADMIN_PASSWORD=$ADMIN_PASSWORD
DATA_DIR=$DATA_DIR
MAX_UPLOAD_MB=2048
EOF
chmod 600 "$ENV_FILE"

say "Creating the background service (auto-starts on boot, restarts on crash)"
cat > /etc/systemd/system/steel-signage.service <<EOF
[Unit]
Description=Steel Signage digital signage server
After=network-online.target
Wants=network-online.target

[Service]
User=$APP_USER
EnvironmentFile=$ENV_FILE
WorkingDirectory=$APP_DIR/server
ExecStart=$(command -v node) server.js
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=$DATA_DIR

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable steel-signage >/dev/null
systemctl restart steel-signage

say "Installing Caddy web server (free automatic HTTPS)"
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi
if [ -n "$DOMAIN" ]; then
  SITE="$DOMAIN"
else
  SITE=":80"
fi
cat > /etc/caddy/Caddyfile <<EOF
$SITE {
	encode gzip
	reverse_proxy 127.0.0.1:$PORT
}
EOF
systemctl enable caddy >/dev/null
systemctl restart caddy

say "Opening ports 80 and 443 in the server firewall"
# Oracle's Ubuntu images block everything except SSH with iptables REJECT rules.
for p in 80 443; do
  iptables -C INPUT -p tcp --dport "$p" -j ACCEPT 2>/dev/null || iptables -I INPUT 5 -p tcp -m state --state NEW --dport "$p" -j ACCEPT
done
netfilter-persistent save >/dev/null 2>&1 || true
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
fi

say "Adding helper commands"
cat > /usr/local/bin/steel-signage-update <<'EOF'
#!/usr/bin/env bash
# Pull the latest Steel Signage from GitHub and restart (keeps all data).
set -e
git -C /opt/steel-signage fetch --depth 1 origin main
git -C /opt/steel-signage reset --hard origin/main
systemctl restart steel-signage
echo "Steel Signage updated and restarted."
EOF
cat > /usr/local/bin/steel-signage-backup <<'EOF'
#!/usr/bin/env bash
# Save all media + playlists + screens into one file in your home folder.
set -e
OUT="${HOME:-/root}/steel-signage-backup-$(date +%F).tar.gz"
tar -czf "$OUT" -C /var/lib steel-signage
echo "Backup saved: $OUT"
EOF
chmod +x /usr/local/bin/steel-signage-update /usr/local/bin/steel-signage-backup

say "Checking that everything is running"
sleep 2
systemctl is-active --quiet steel-signage || { journalctl -u steel-signage -n 30 --no-pager; die "Steel Signage service did not start."; }
curl -fsS -o /dev/null "http://127.0.0.1:$PORT/player" || die "Server is not answering on port $PORT."

PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org || echo YOUR-SERVER-IP)"
if [ -n "$DOMAIN" ]; then URL="https://$DOMAIN"; else URL="http://$PUBLIC_IP"; fi

cat <<EOF

=====================================================================
  ✅ Steel Signage is live!

  Admin panel : $URL
  Username    : admin
  Password    : $ADMIN_PASSWORD

  On each TV, open the Steel Signage app and enter:  $URL

  Useful commands:
    steel-signage-update          get the newest version from GitHub
    steel-signage-backup          back up media, playlists and screens
    sudo systemctl restart steel-signage
    sudo journalctl -u steel-signage -f     (live logs)

  If the page does not open from your browser, add ingress rules for
  TCP ports 80 and 443 in Oracle Console → Networking → Virtual Cloud
  Networks → your VCN → Security Lists → Default Security List.
=====================================================================
EOF
