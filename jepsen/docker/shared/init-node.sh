#!/bin/bash

apt update
apt install -y -V --fix-missing --no-install-recommends \
    apt-transport-https \
    wget \
    ca-certificates \
    gnupg \
    curl

apt install -y openssh-server sudo
/etc/init.d/ssh start

mkdir -p ~/.ssh/
cat /root/shared/jepsen-bot.pub > ~/.ssh/authorized_keys
