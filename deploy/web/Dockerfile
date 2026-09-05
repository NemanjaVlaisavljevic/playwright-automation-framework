# Build stage - produces the dashboard's static production bundle. Node never ships in the
# runtime image; it only ever exists in this discarded build stage.
FROM node:24-slim AS build

WORKDIR /app
COPY runner-dashboard/package.json runner-dashboard/package-lock.json ./
RUN npm ci
COPY runner-dashboard/ ./
RUN npm run build

# Runtime stage - Caddy serves the built static files directly (SPA fallback to index.html),
# reverse-proxies /api/* (including long-lived SSE connections) to runner-service, terminates TLS,
# and adds security headers - see deploy/web/Caddyfile. No Node, no separate frontend process; this
# is the whole "same origin" story for the browser.
FROM caddy:2-alpine

COPY --from=build /app/dist /srv
COPY deploy/web/Caddyfile /etc/caddy/Caddyfile
