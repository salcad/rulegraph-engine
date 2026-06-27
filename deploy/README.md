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

From the repository root:

```bash
# Optional: set secrets in a .env file next to docker-compose.yml
#   NEO4J_PASSWORD=<a real secret>
#   OPENROUTER_API_KEY=<key>        # only if you want the LLM extractor
#   RULEGRAPH_LLM_ENABLED=true      #   "
docker compose up -d --build
```

This starts `rulegraph-neo4j`, `rulegraph-backend` (on `127.0.0.1:8074`), and
`rulegraph-ui` (on `127.0.0.1:8073`). Check them:

```bash
docker compose ps
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
docker compose up -d --build      # rebuilds changed images, recreates containers
```

The UI's API base URL is baked in at build time, so the image must be rebuilt
(not just restarted) if you ever change the subdomain or the API prefix.

## Notes

- **Neo4j** is not published to the host in this stack; only the backend reaches
  it over the compose network. Add a `127.0.0.1:7474:7474` mapping temporarily if
  you need the Neo4j browser.
- **First report is slow.** The first request for a firm ingests the PDF and runs
  the pipeline (and, if enabled, an LLM call). The vhost allows a 120s read
  timeout for `/rulegraph-api/` to cover this; later requests are fast.
- **Local development** is unchanged: `rulegraph-engine/docker-compose.yml` runs
  the backend alone and the viewer runs in the Vite dev server. That stack shares
  container names with this one, so run only one at a time.
