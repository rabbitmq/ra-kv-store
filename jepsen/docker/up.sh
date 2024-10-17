#!/bin/bash

docker compose exec --no-TTY control /root/shared/init-control.sh &
docker compose exec --no-TTY n1 /root/shared/init-node.sh &
docker compose exec --no-TTY n2 /root/shared/init-node.sh &
docker compose exec --no-TTY n3 /root/shared/init-node.sh &

wait
