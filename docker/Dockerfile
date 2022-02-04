FROM bitnami/pgbouncer:latest AS builder

USER root

############TOOLS############

RUN apt-get update && apt-get install -y \
	autoconf \
	automake \
	build-essential \
	git \
	libcurl4-openssl-dev \
	libevent-dev \
	libpam0g-dev \
	libssl-dev \
	libtool \
	pandoc \
	pkg-config \
	python3 \
  && rm -rf /var/lib/apt/lists/*

############PGBOUNCER INSTALL############
 
RUN git clone --depth 1 https://github.com/pgbouncer/pgbouncer.git \
	&& cd pgbouncer \
	&& perl -pi -e 's/MAX_USERNAME\s*\d.*/MAX_USERNAME	16384/g' include/bouncer.h \
	&& git submodule init \
	&& git submodule update \
	&& ./autogen.sh \
	&& ./configure --build=x86_64 --with-pam \
	&& make \
	&& make install

############PAM HTTP INSTALL############

RUN git clone --depth 1 https://github.com/beatgammit/pam-http \
	&& cd pam-http \
	&& FIND="\bprintf("; REPLACE="//printf("; sed -i "s|${FIND}|${REPLACE}|g" src/mypam.c \
	&& FIND="curl_easy_setopt(pCurl, CURLOPT_SSL_VERIFYPEER"; REPLACE="//curl_easy_setopt(pCurl, CURLOPT_SSL_VERIFYPEER" sed -i "s|${FIND}|${REPLACE}|g" src/mypam.c \
	&& FIND="curl_easy_setopt(pCurl, CURLOPT_SSL_VERIFYHOST"; REPLACE="//curl_easy_setopt(pCurl, CURLOPT_SSL_VERIFYHOST" sed -i "s|${FIND}|${REPLACE}|g" src/mypam.c \
	&& FIND="curl_easy_setopt(pCurl, CURLOPT_TIMEOUT, 1)"; REPLACE="curl_easy_setopt(pCurl, CURLOPT_TIMEOUT, 30)" sed -i "s|${FIND}|${REPLACE}|g" src/mypam.c \
	&& make \
	&& mkdir -p /lib/security \
	&& mv mypam.so /lib/security/ \
	&& cd .. \
	&& rm -rf pam-http

FROM bitnami/pgbouncer:latest

USER root

COPY --from=builder /usr/local/bin/pgbouncer /opt/bitnami/pgbouncer/bin/pgbouncer
COPY --from=builder /lib/security/mypam.so /lib/security/mypam.so
  
RUN curl -L https://github.com/msoap/shell2http/releases/download/v1.14.1/shell2http_1.14.1_linux_amd64.deb -o ./shell2http.deb \
	&& dpkg -i ./shell2http.deb \
	&& rm ./shell2http.deb
	
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]

CMD ["/opt/bitnami/scripts/pgbouncer/run.sh"]