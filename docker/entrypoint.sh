#!/bin/bash

set -e

if [[ ! -z "$PGBOUNCER_AUTH_URL" ]]; then
	if [[ ! -z "$PGBOUNCER_AUTH_TYPE" ]] && [[ "$PGBOUNCER_AUTH_TYPE" != "pam" ]]; then
		echo "PGBOUNCER_AUTH_URL requires PGBOUNCER_AUTH_TYPE:pam - current value:$PGBOUNCER_AUTH_TYPE" >&2
		exit 1
	fi
	echo "configuring PGBOUNCER_AUTH_URL:$PGBOUNCER_AUTH_URL"
	TEMP_FILE=$(mktemp)
	echo "auth sufficient /lib/security/mypam.so url=${PGBOUNCER_AUTH_URL}" > $TEMP_FILE
	echo "account sufficient /lib/security/mypam.so" >> $TEMP_FILE
	cat /etc/pam.d/common-auth >> $TEMP_FILE
	cat $TEMP_FILE > /etc/pam.d/common-auth
	rm $TEMP_FILE
	export PGBOUNCER_AUTH_TYPE="pam"
fi

RELOAD_BASIC_AUTH=""
if [[ ! -z "$RELOAD_USERNAME" ]] || [[ ! -z "$RELOAD_PASSWORD" ]]; then
	RELOAD_BASIC_AUTH="-basic-auth=$RELOAD_USERNAME:$RELOAD_PASSWORD"
fi

if [[ "${RELOAD_NO_AUTH,,}" = "true" ]] || [[ ! -z "$RELOAD_BASIC_AUTH" ]]; then
	echo "reload api enabled - 0.0.0.0:6488/reload"
	shell2http -port=6488 "$RELOAD_BASIC_AUTH" /reload "su -c \"echo 'RELOAD' | psql -p 6432 pgbouncer\" pgbouncer" &
else
	echo "reload api disabled"
fi

/opt/bitnami/scripts/pgbouncer/entrypoint.sh $@

# export POSTGRESQL_USERNAME=yugabyte
# export POSTGRESQL_PASSWORD=yugabyte
# export POSTGRESQL_DATABASE=yugabyte
# export POSTGRESQL_HOST=192.168.1.71
# export POSTGRESQL_PORT=5433
# export PGBOUNCER_AUTH_URL=http://mockbin.org/bin/1ad4f65c-b9e5-4736-aa56-2309d1d6a828

# docker run -it \
	# --env "POSTGRESQL_USERNAME=yugabyte" \
	# --env "POSTGRESQL_PASSWORD=yugabyte" \
	# --env "POSTGRESQL_DATABASE=yugabyte" \
	# --env "POSTGRESQL_HOST=192.168.1.71" \
	# --env "PGBOUNCER_AUTH_USER=yugabyte" \
	# --env "POSTGRESQL_PORT=5433" \
	# --env "PGBOUNCER_AUTH_URL=http://mockbin.org/bin/1ad4f65c-b9e5-4736-aa56-2309d1d6a828" \
	# -p 6432:6432 \
	# regbo/docker:latest