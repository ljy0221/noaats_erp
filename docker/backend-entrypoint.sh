#!/usr/bin/env bash
set -e

# postgres 대기 (compose depends_on: condition=service_healthy가 있어도 race 방지)
: "${DB_HOST:=postgres}"
: "${DB_PORT:=5432}"
echo "[entrypoint] waiting for ${DB_HOST}:${DB_PORT} ..."
for i in $(seq 1 60); do
  if nc -z "${DB_HOST}" "${DB_PORT}"; then
    echo "[entrypoint] db ready"
    break
  fi
  sleep 1
done

exec java $JAVA_OPTS -jar /app/app.jar
