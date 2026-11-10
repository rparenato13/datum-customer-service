#!/bin/bash
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/"${SERVER_PORT:-8080}"
printf 'GET /actuator/health HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r -t 2 status <&3
[[ "$status" == HTTP/1.*" 200 "* ]]
