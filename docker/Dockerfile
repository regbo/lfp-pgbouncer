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

############UTILS INSTALL############

RUN apt-get update \
  && apt-get install -y \
  nano \
  wget \
  curl \
  && rm -rf /var/lib/apt/lists/*
  
############JAVA INSTALL############

RUN apt-get update \
  && apt-get install -y \
  openjdk-11-jre-headless \
  && rm -rf /var/lib/apt/lists/*

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

############CADDY INSTALL############

RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@latest
RUN xcaddy build \
    --with github.com/gamalan/caddy-tlsredis \
    --with github.com/mholt/caddy-l4 \
    --with github.com/mholt/caddy-dynamicdns \
    --with github.com/caddy-dns/route53 \
  && mv ./caddy /usr/local/bin/caddy
RUN go install github.com/pete911/certinfo@latest

############COPY FILES############

ARG APP_JAR
COPY ${APP_JAR} /app.jar
RUN du -h /app.jar

ENTRYPOINT ["sh", "-c", "java -XX:+UnlockExperimentalVMOptions -XX:MaxRAMPercentage=10 --add-opens java.base/jdk.internal.loader=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar /app.jar"]

CMD ["/opt/bitnami/scripts/pgbouncer/run.sh"]