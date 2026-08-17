# Deploying `site/`

Target: `https://meshline.praharilabs.com`, served by Caddy on the OCI box
(`144.24.141.188`) alongside the other Prahari Labs subdomains.

## Status

| Step | State |
|---|---|
| DNS `meshline` A → `144.24.141.188` | **Done** — created via the GoDaddy API, TTL 600, resolving |
| Files copied to the server | **Not done** — port 22 unreachable from the machine this was prepared on |
| Caddy site block | **Not done** — same reason |

Until the last two are done, `meshline.praharilabs.com` resolves but fails TLS,
because Caddy has no site block for the host and so has never requested a
certificate for it.

## Steps

There is no build step. `index.html` and `privacy.html` are self-contained.

```sh
# 1. copy the two pages up
scp -i /path/to/ssh-key-2026-06-02.key site/*.html \
    ubuntu@144.24.141.188:/tmp/meshline/

# 2. put them where the other sites live, then serve the host
ssh -i /path/to/ssh-key-2026-06-02.key ubuntu@144.24.141.188
sudo mkdir -p /var/www/meshline
sudo cp /tmp/meshline/*.html /var/www/meshline/
sudo caddy reload --config /etc/caddy/Caddyfile
```

Caddy block to add — match the style of the existing entries rather than
pasting this verbatim, since the webroot convention on that box was not
verified when this file was written:

```
meshline.praharilabs.com {
    root * /var/www/meshline
    file_server
    encode gzip zstd
}
```

Caddy will obtain the certificate on the first request once the block is live
and DNS resolves, which it already does.

## Verify

```sh
curl -sSI https://meshline.praharilabs.com | head -3
curl -sSI https://meshline.praharilabs.com/privacy.html | head -3
```

Both should return `200` and `Server: Caddy`.

## The landing page is a separate deploy

`praharilabs.com` is served from the same box out of its own webroot, so
pushing `prahari-labs-site` to GitHub does **not** update it. The MeshLine
section will not appear on the live landing page until that repo's
`index.html` is copied up the same way.

## Privacy policy URL

Play requires a reachable privacy policy. Once deployed, the listing URL is
`https://meshline.praharilabs.com/privacy.html`. That page is `PRIVACY.md`
rendered — keep the two in step.
