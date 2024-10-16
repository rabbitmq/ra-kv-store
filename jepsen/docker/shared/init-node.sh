#!/bin/bash

apt update
apt install -y -V --fix-missing --no-install-recommends \
    apt-transport-https \
    wget \
    ca-certificates \
    gnupg \
    curl

apt install -y \
    build-essential \
    bzip2 \
    curl \
    faketime \
    iproute2 \
    iptables \
    iputils-ping \
    libzip4 \
    logrotate \
    man \
    man-db \
    net-tools \
    ntpdate \
    openssh-server \
    psmisc \
    python3 \
    rsyslog \
    sudo \
    tar \
    unzip \
    wget

# echo 'PubkeyAcceptedKeyTypes +ssh-rsa' > /etc/ssh/sshd_config
/etc/init.d/ssh start

mkdir -p ~/.ssh/
cat /root/shared/jepsen-bot.pub > ~/.ssh/authorized_keys
