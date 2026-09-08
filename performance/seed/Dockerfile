# D4.4.1a/D4.4.1c - the seed tool needs only a psql client and a shell; pinned to the exact same
# postgres:17-alpine tag deploy/docker-compose.yml's own `postgres` service already uses, so the
# client version is guaranteed compatible with the server it talks to. No fixed ENTRYPOINT/CMD -
# this one image backs two distinct Compose services (`performance-seed` runs `./seed.sh`,
# `performance-seed-live` runs `./seed-live-run.sh insert|delete`), each supplying its own command.
FROM postgres:17-alpine
WORKDIR /seed
COPY seed.sql seed.sh seed-live-run.sql seed-live-run.sh ./
COPY assets ./assets
RUN chmod +x seed.sh seed-live-run.sh
