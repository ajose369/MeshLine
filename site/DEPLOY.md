# Deploying `site/`

Live at **https://meshline.praharilabs.com** — deployed 17 August 2026.

There is no build step. `index.html` and `privacy.html` are self-contained:
styles and scripts inline, no external requests.

## How this host actually works

Worth knowing before touching anything, because it is not the usual
`/etc/caddy/Caddyfile` layout:

- The edge proxy is a **container**, `n8n-caddy` (`caddy:2.8-alpine`), the only
  thing bound to ports 80/443. There is no system Caddy — `systemctl status
  caddy` reports nothing and `caddy` is not on the host PATH.
- Its config is **`/srv/n8n/Caddyfile`**, mounted read-only into the container.
- Static sites live in **`/srv/<name>-site`** on the host and must be
  bind-mounted into the container individually in `/srv/n8n/docker-compose.yml`.
  A site block pointing at a path that is not mounted will 404.
- `praharilabs.com` is served from `/srv/praharilabs-site`, MeshLine from
  `/srv/meshline-site`.

## What is in place

`/srv/n8n/docker-compose.yml`, under `n8n-caddy.volumes`:

```yaml
- /srv/meshline-site:/srv/meshline-site:ro
```

`/srv/n8n/Caddyfile`:

```
meshline.praharilabs.com {
    encode gzip
    root * /srv/meshline-site
    file_server
}
```

DNS: `meshline` A → `144.24.141.188`, TTL 600, created via the GoDaddy API.
Caddy obtained the certificate automatically on first request.

## Updating the pages

Content-only changes need no container restart — the mount is live:

```sh
KEY=/path/to/ssh-key-2026-06-02.key
scp -i $KEY site/*.html ubuntu@144.24.141.188:/tmp/
ssh -i $KEY ubuntu@144.24.141.188 \
  'sudo cp /tmp/index.html /tmp/privacy.html /srv/meshline-site/ && \
   sudo chmod 644 /srv/meshline-site/*.html'
```

Only if you change the **Caddyfile** does Caddy need to reload, and only if you
change the **mounts** does the container need recreating:

```sh
# validate first — a syntax error here takes every site on the box down
sudo docker run --rm -v /srv/n8n/Caddyfile:/etc/caddy/Caddyfile:ro \
  caddy:2.8-alpine caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

cd /srv/n8n && sudo docker compose up -d n8n-caddy   # name the service:
                                                     # a bare `up -d` would also
                                                     # recreate n8n
```

Back up `/srv/n8n/Caddyfile` and `docker-compose.yml` first; the convention on
this box is `.bak-YYYYMMDD-HHMMSS`.

## Verify

```sh
for h in meshline.praharilabs.com praharilabs.com jyotir.praharilabs.com \
         pauseos.praharilabs.com legacy.praharilabs.com \
         sentinel.praharilabs.com vela.praharilabs.com; do
  printf "%-32s " "$h"
  curl -sS -o /dev/null -w "%{http_code} tls=%{ssl_verify_result}\n" "https://$h"
done
```

All should be `200 tls=0`. Check the neighbours too, not just the one you
changed — they share the single Caddy instance.

## The landing page is a separate deploy

`praharilabs.com` is served from `/srv/praharilabs-site` on this same box, so
pushing the `prahari-labs-site` repo to GitHub does **not** update the live
site. Copy `index.html` up the same way.

## Privacy policy URL

The Play listing URL is `https://meshline.praharilabs.com/privacy.html`. That
page is `PRIVACY.md` rendered — keep the two in step.
