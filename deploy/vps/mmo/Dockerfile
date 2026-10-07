# The MMO server (mmo/, Open MMORPG): the Unity Linux build runs in a plain Ubuntu. SQLite for the
# demo database; the build itself is mounted from /opt/tz-mmo/server, not baked in.
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends libsqlite3-0 libsqlite3-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /data
