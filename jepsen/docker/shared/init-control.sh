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
  libjna-java gnuplot graphviz openssh-client git

# Java
export JAVA_PATH="/usr/lib/jdk-21"
JAVA_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz"
wget --progress dot:giga --output-document "$JAVA_PATH.tar.gz" $JAVA_URL
 
mkdir -p $JAVA_PATH
tar --extract --file "$JAVA_PATH.tar.gz" --directory "$JAVA_PATH" --strip-components 1
rm "$JAVA_PATH.tar.gz"
ln -s "$JAVA_PATH/bin/java" /usr/bin/java

# Leiningen
wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
mv lein /usr/bin/lein
chmod u+x /usr/bin/lein
lein -v
