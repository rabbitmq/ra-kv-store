#!/bin/bash

set -ex

# script to provision and configure the Jepsen controller
# this controller will compile and package the RA KV store
# and then will run the Jepsen tests

sudo apt-get update

sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -V --fix-missing --no-install-recommends \
    apt-transport-https \
    wget \
    ca-certificates \
    gnupg \
    curl

## Team RabbitMQ's signing key
curl -1sLf "https://keys.openpgp.org/vks/v1/by-fingerprint/0A9AF2115F4687BD29803A206B73A36E6026DFCA" | sudo gpg --dearmor | sudo tee /usr/share/keyrings/com.rabbitmq.team.gpg > /dev/null

sudo tee /etc/apt/sources.list.d/rabbitmq.list <<EOF
## Modern Erlang/OTP releases
##
deb [arch=amd64 signed-by=/usr/share/keyrings/com.rabbitmq.team.gpg] https://deb1.rabbitmq.com/rabbitmq-erlang/debian/bookworm bookworm main
deb [arch=amd64 signed-by=/usr/share/keyrings/com.rabbitmq.team.gpg] https://deb2.rabbitmq.com/rabbitmq-erlang/debian/bookworm bookworm main
EOF

export ERLANG_VERSION="1:26*"
sudo mkdir -p /etc/apt/preferences.d/
sudo tee --append /etc/apt/preferences.d/erlang <<EOF
Package: erlang*
Pin: version $ERLANG_VERSION
Pin-Priority: 1000
EOF

# install Erlang and some utilities
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -V --fix-missing --no-install-recommends \
  erlang-nox \
  erlang-dev \
  git \
  gnuplot \
  graphviz \
  libjna-java \
  make \
  openssh-client
 
# install Java
export JAVA_PATH="/usr/lib/jdk-21"
JAVA_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz"
wget --progress dot:giga --output-document jdk.tar.gz $JAVA_URL
 
sudo mkdir -p $JAVA_PATH
sudo tar --extract --file jdk.tar.gz --directory "$JAVA_PATH" --strip-components 1
rm jdk.tar.gz
sudo ln -s "$JAVA_PATH/bin/java" /usr/bin/java

# install lein (to compile and launch the Jepsen tests)
wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
sudo mv lein /usr/bin/lein
sudo chmod u+x /usr/bin/lein
lein -v

# configure SSH
touch ~/.ssh/known_hosts
chmod 600 jepsen-bot 
echo "StrictHostKeyChecking no" >> ~/.ssh/config

set +e
# get, compile, and package the RA KV store
git clone https://github.com/rabbitmq/ra-kv-store.git ~/ra_kv_store
make -C ~/ra_kv_store rel-jepsen-local
git --no-pager -C ~/ra_kv_store/deps/ra log -1 --pretty=short
set -e
