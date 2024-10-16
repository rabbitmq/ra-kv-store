#!/bin/bash

apt update
apt-get install -y -V --fix-missing --no-install-recommends \
    apt-transport-https \
    wget \
    ca-certificates \
    gnupg \
    curl

# Jepsen dependencies
apt install -y -V --fix-missing --no-install-recommends \
  openjdk-17-jdk libjna-java gnuplot graphviz openssh-client

wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
mv lein /usr/bin/lein
chmod u+x /usr/bin/lein
lein -v
