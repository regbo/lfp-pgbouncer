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

if [[ "${API,,}" = "true" ]]; then
	node /script.js &
else
	echo "api disabled"
fi

/opt/bitnami/scripts/pgbouncer/entrypoint.sh $@
