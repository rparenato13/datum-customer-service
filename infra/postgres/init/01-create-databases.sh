#!/bin/sh
set -eu

: "${CUSTOMER_DB_PASSWORD:?CUSTOMER_DB_PASSWORD não definida}"
: "${NOTIFICATION_DB_PASSWORD:?NOTIFICATION_DB_PASSWORD não definida}"

# As variáveis psql com :'nome' escapam as senhas como literais SQL.
# Este bootstrap roda somente na inicialização de um volume vazio.
psql --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 \
    --set=customer_password="$CUSTOMER_DB_PASSWORD" \
    --set=notification_password="$NOTIFICATION_DB_PASSWORD" <<'SQL'
CREATE ROLE customer_app LOGIN PASSWORD :'customer_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE notification_app LOGIN PASSWORD :'notification_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

CREATE DATABASE customer_db OWNER customer_app;
CREATE DATABASE notification_db OWNER notification_app;

-- CONNECT é concedido a PUBLIC por padrão; é necessário revogá-lo.
REVOKE ALL ON DATABASE customer_db FROM PUBLIC;
REVOKE ALL ON DATABASE notification_db FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;

\connect customer_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO customer_app;

\connect notification_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO notification_app;
SQL
