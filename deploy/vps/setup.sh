#!/bin/sh
# One-time setup of the game VPS (Ubuntu 22.04+/Debian 12+), run as root:
#   curl -fsSL https://raw.githubusercontent.com/zzzloj/Tz-/main/deploy/vps/setup.sh | sh
# or copy this file over and run `sh setup.sh`. Safe to run again: it keeps .env and the key.
#
# What it does:
#   - installs Docker with the compose plugin (Docker's own apt repository);
#   - creates the user tzdeploy (in the docker group) that GitHub Actions logs in as;
#   - makes an SSH key for it and prints the private half once, for the VPS_SSH_KEY secret;
#   - writes /opt/tz/.env: a random database password and TZ_HOST = <ip>.sslip.io;
#   - opens 22, 80, 443 if ufw is on.
# The game itself arrives with the first run of .github/workflows/engine-deploy.yml.
set -eu

[ "$(id -u)" = 0 ] || { echo "run as root (sudo sh setup.sh)"; exit 1; }
. /etc/os-release
case "$ID" in ubuntu|debian) ;; *) echo "Ubuntu or Debian expected, found $ID"; exit 1;; esac

echo "== Docker"
if ! docker compose version >/dev/null 2>&1; then
    apt-get update -q
    apt-get install -yq ca-certificates curl
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL "https://download.docker.com/linux/$ID/gpg" -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/$ID $VERSION_CODENAME stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -q
    apt-get install -yq docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
systemctl enable --now docker
docker compose version

echo "== User tzdeploy"
id tzdeploy >/dev/null 2>&1 || useradd -m -s /bin/bash tzdeploy
usermod -aG docker tzdeploy
install -d -m 700 -o tzdeploy -g tzdeploy /home/tzdeploy/.ssh
KEY=/home/tzdeploy/.ssh/deploy_ed25519
NEW_KEY=0
if [ ! -f "$KEY" ]; then
    su tzdeploy -c "ssh-keygen -q -t ed25519 -N '' -C tz-github-deploy -f $KEY"
    NEW_KEY=1
fi
touch /home/tzdeploy/.ssh/authorized_keys
grep -qF "$(cat $KEY.pub)" /home/tzdeploy/.ssh/authorized_keys || cat "$KEY.pub" >> /home/tzdeploy/.ssh/authorized_keys
chown tzdeploy:tzdeploy /home/tzdeploy/.ssh/authorized_keys
chmod 600 /home/tzdeploy/.ssh/authorized_keys

echo "== /opt/tz"
install -d -o tzdeploy -g tzdeploy /opt/tz
IP=$(curl -fsS4 https://api.ipify.org || hostname -I | awk '{print $1}')
if [ ! -f /opt/tz/.env ]; then
    cat > /opt/tz/.env <<EOF
# Game server settings (deploy/vps/docker-compose.yml). Restart after a change:
#   cd /opt/tz && docker compose up -d
DB_PASSWORD=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
# The name Caddy gets a certificate for; with a domain, put it here.
TZ_HOST=$(echo "$IP" | tr . -).sslip.io
# Game admins: comma-separated character names.
TZ_ADMINS=
EOF
    chown tzdeploy:tzdeploy /opt/tz/.env
    chmod 600 /opt/tz/.env
fi

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    echo "== ufw: opening 22, 80, 443"
    ufw allow 22/tcp; ufw allow 80/tcp; ufw allow 443/tcp
fi

echo
echo "================================================================"
echo "Done. Add these repository secrets on GitHub"
echo "(Settings -> Secrets and variables -> Actions -> New repository secret):"
echo
echo "  VPS_HOST     = $IP"
echo "  VPS_USER     = tzdeploy"
if [ "$NEW_KEY" = 1 ]; then
    echo "  VPS_SSH_KEY  = everything between the lines below, including BEGIN/END:"
    echo "----------------------------------------------------------------"
    cat "$KEY"
    echo "----------------------------------------------------------------"
else
    echo "  VPS_SSH_KEY  = already made earlier: sudo cat $KEY"
fi
echo
echo "The game will answer at https://$(grep ^TZ_HOST /opt/tz/.env | cut -d= -f2) after the first deploy."
echo "================================================================"
