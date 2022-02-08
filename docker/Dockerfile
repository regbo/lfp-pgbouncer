FROM bitnami/pgbouncer:latest AS pgbouncer-builder

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

COPY --from=pgbouncer-builder /usr/local/bin/pgbouncer /opt/bitnami/pgbouncer/bin/pgbouncer
COPY --from=pgbouncer-builder /lib/security/mypam.so /lib/security/mypam.so
  
############GOLANG INSTALL############

ENV GODIR=$WORKDIR/go
ENV GOROOT="${GODIR}/goroot"
ENV GOPATH="${GODIR}/gopath"
RUN curl -sL https://git.io/vQhTU | bash \
  && ln -s $HOME/.go $GOROOT \
  && ln -s $HOME/go $GOPATH \
  && rm -rf /var/lib/apt/lists/*
ENV PATH=$GOROOT/bin:$PATH
ENV PATH=$GOPATH/bin:$PATH

############JAVA INSTALL############

RUN apt-get update \
  && apt-get install -y openjdk-11-jre-headless \
  && rm -rf /var/lib/apt/lists/*
  
############COPY FILES############
	
COPY $APP_JAR /app.jar
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]

CMD ["/opt/bitnami/scripts/pgbouncer/run.sh"]