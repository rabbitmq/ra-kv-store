#!/bin/bash

set -ex

# script to provision and configure the Jepsen controller
# this controller will compile and package the RA KV store
# and then will run the Jepsen tests

sudo apt-get update

sudo apt-get install -y -V --fix-missing --no-install-recommends \
    apt-transport-https \
    wget \
    ca-certificates \
    gnupg \
    curl

echo "deb https://ppa1.novemberain.com/rabbitmq/rabbitmq-erlang/deb/debian buster main" | sudo tee --append /etc/apt/sources.list.d/rabbitmq-erlang.list
echo "deb https://ppa2.novemberain.com/rabbitmq/rabbitmq-erlang/deb/debian buster main" | sudo tee --append /etc/apt/sources.list.d/rabbitmq-erlang.list
wget https://github.com/rabbitmq/signing-keys/releases/download/3.0/cloudsmith.rabbitmq-erlang.E495BB49CC4BBE5B.key
sudo apt-key add cloudsmith.rabbitmq-erlang.E495BB49CC4BBE5B.key

export ERLANG_VERSION="1:26*"
sudo mkdir -p /etc/apt/preferences.d/
sudo tee --append /etc/apt/preferences.d/erlang <<EOF
Package: erlang*
Pin: version $ERLANG_VERSION
Pin-Priority: 1000
EOF

# install Erlang a few utilities
sudo apt-get update
sudo apt-get install -y -V --fix-missing --no-install-recommends git make gnuplot erlang-nox erlang-dev

# install Java 8 (needed by Jepsen)
export JAVA_PATH="/usr/lib/jdk-8"
sudo wget --progress dot:giga --output-document "$JAVA_PATH.tar.gz" https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u392-b08/OpenJDK8U-jdk_x64_linux_hotspot_8u392b08.tar.gz
sudo mkdir $JAVA_PATH
sudo tar --extract --file "$JAVA_PATH.tar.gz" --directory "$JAVA_PATH" --strip-components 1
export JAVA_HOME=$JAVA_PATH
export PATH=$PATH:$JAVA_HOME/bin
echo "export JAVA_HOME=$JAVA_PATH" >> .profile
echo "export PATH=$PATH:$JAVA_PATH/bin" >> .profile

# install lein (to compile and launch the Jepsen tests)
wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
sudo mv lein /usr/bin/lein
sudo chmod u+x /usr/bin/lein
lein -v

# configure SSH
touch ~/.ssh/known_hosts
chmod 600 jepsen-bot 
echo "StrictHostKeyChecking no" >> ~/.ssh/config

# get, compile, and package the RA KV store
git clone https://github.com/rabbitmq/ra-kv-store.git ra_kv_store
make -C ~/ra_kv_store rel-jepsen-local
cd ra_kv_store
echo $(git log -1 --pretty="%h %B")
cd ..
