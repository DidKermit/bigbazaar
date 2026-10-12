#!/bin/bash
# Local development only: one PostgreSQL server, one database and one login per service (SRS 8.1).
# Each service user owns only its own database and cannot connect to the others.
set -euo pipefail

SERVICES="identity catalog commerce payment notification trust recommendation platform"
PASSWORD_SUFFIX="${SERVICE_DB_PASSWORD_SUFFIX:-_svc_dev}"

for svc in $SERVICES; do
  db="${svc}_db"
  user="${svc}_svc"
  password="${svc}${PASSWORD_SUFFIX}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    CREATE ROLE ${user} LOGIN PASSWORD '${password}';
    CREATE DATABASE ${db} OWNER ${user};
    REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
SQL
done
