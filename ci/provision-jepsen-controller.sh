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

echo "deb https://ppa1.novemberain.com/rabbitmq/rabbitmq-erlang/deb/debian bookworm main" | sudo tee --append /etc/apt/sources.list.d/rabbitmq-erlang.list
echo "deb https://ppa2.novemberain.com/rabbitmq/rabbitmq-erlang/deb/debian bookworm main" | sudo tee --append /etc/apt/sources.list.d/rabbitmq-erlang.list
wget https://github.com/rabbitmq/signing-keys/releases/download/3.0/cloudsmith.rabbitmq-erlang.E495BB49CC4BBE5B.key
sudo apt-key add cloudsmith.rabbitmq-erlang.E495BB49CC4BBE5B.key

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
JAVA_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz"
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
