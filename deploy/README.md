# Deploying RuleGraph to a VPS (one subdomain)

This deploys the UI and the engine API behind a single subdomain,
`rulegraph.javanet.cyou`, on a plain Docker + nginx VPS.

```
                         rulegraph.javanet.cyou (443, TLS via certbot)
                                      │
                              host nginx vhost
                  ┌───────────────────┴───────────────────┐
            location /                           location /rulegraph-api/
                  │                                        │
        127.0.0.1:8073 (ui)                      127.0.0.1:8074 (backend)
        static SPA, nginx                        Spring Boot API
                                                          │
                                                   neo4j (compose network)
```

Because everything is one origin, the browser calls `/rulegraph-api/` relatively,
so there is **no CORS to configure** — the UI is built with `VITE_API_BASE_URL=""`.
Both containers publish to `127.0.0.1` only; the host nginx is the sole public door.

## 1. Bring up the containers

Each module has its own compose; they run as independent stacks. Bring up the
backend first, then the UI.

```bash
# Backend (Neo4j + engine API on 127.0.0.1:8074)
cd rulegraph-engine
# Optional: set secrets in a .env file next to this compose
#   NEO4J_PASSWORD=<a real secret>
#   OPENROUTER_API_KEY=<key>        # only if you want the LLM extractor
#   RULEGRAPH_LLM_ENABLED=true      #   "
docker compose up -d --build

# UI (static viewer on 127.0.0.1:8073)
cd ../rulegraph-ui
docker compose up -d --build
```

This starts `rulegraph-neo4j`, `rulegraph-backend` (on `127.0.0.1:8074`), and
`rulegraph-ui` (on `127.0.0.1:8073`). Check them:

```bash
curl -s http://127.0.0.1:8074/rulegraph-api/health   # backend health
curl -sI http://127.0.0.1:8073/                       # UI index
```

## 2. Install the nginx vhost

```bash
sudo cp rulegraph-engine/deploy/nginx/rulegraph.javanet.cyou.conf \
        /etc/nginx/sites-available/rulegraph.javanet.cyou.conf
sudo ln -s /etc/nginx/sites-available/rulegraph.javanet.cyou.conf \
           /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

Point the `rulegraph.javanet.cyou` DNS A/AAAA record at the VPS first, so the
ACME challenge in step 3 can resolve.

## 3. Get a TLS certificate

```bash
sudo certbot --nginx -d rulegraph.javanet.cyou
```

certbot adds the `listen 443 ssl` + certificate lines to the vhost and creates
the HTTP→HTTPS redirect. The `/` and `/rulegraph-api/` location blocks carry over
unchanged. Reload is automatic; renewals are handled by certbot's timer.

Open `https://rulegraph.javanet.cyou/` — the viewer loads and its API calls go to
`https://rulegraph.javanet.cyou/rulegraph-api/...` on the same origin.

## Updating

```bash
git pull
(cd rulegraph-engine && docker compose up -d --build)   # backend
(cd rulegraph-ui     && docker compose up -d --build)   # UI
```

Rebuild only the module you changed. The UI's API base URL is baked in at build
time, so its image must be rebuilt (not just restarted) if you ever change the
subdomain or the API prefix.

## Notes

- **Neo4j** is published to `127.0.0.1` only (browser on 7474, Bolt on 7687) for
  local access; the backend itself reaches it over the compose network, so nothing
  is exposed to the public internet.
- **First report is slow.** The first request for a firm ingests the PDF and runs
  the pipeline (and, if enabled, an LLM call). The vhost allows a 120s read
  timeout for `/rulegraph-api/` to cover this; later requests are fast.
- **Local development** uses the same backend compose (`rulegraph-engine/`) with
  the viewer in the Vite dev server instead of the UI container. The dev server
  proxies `/rulegraph-api` to `127.0.0.1:8074`, so it is same-origin too.
