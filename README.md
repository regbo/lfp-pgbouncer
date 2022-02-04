# lfp-pgbouncer

Extension of pgbouncer that adds an external auth url:
https://github.com/bitnami/bitnami-docker-pgbouncer

```
docker run -it \
        --env "POSTGRESQL_USERNAME=yugabyte" \
        --env "POSTGRESQL_PASSWORD=yugabyte" \
        --env "POSTGRESQL_DATABASE=yugabyte" \
        --env "POSTGRESQL_HOST=192.168.1.71" \
        --env "PGBOUNCER_SET_DATABASE_USER=yes" \
        --env "POSTGRESQL_PORT=5433" \
        --env "PGBOUNCER_AUTH_URL=http://auth-host:8080" \
        --env "RELOAD_USERNAME=user" \
        --env "RELOAD_PASSWORD=pass" \
        -p "6432:6432" \
        -p "6488:6488" \
        regbo/lfp-pgbouncer:latest
```
