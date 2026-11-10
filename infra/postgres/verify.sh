#!/bin/sh
set -eu

# Execute na raiz do projeto. Argumentos opcionais são encaminhados ao Compose,
# por exemplo: sh infra/postgres/verify.sh --env-file .env.example -p datum-check
docker compose "$@" exec -T postgres sh -s <<'SH'
set -eu

query() {
    PGUSER="$1" PGPASSWORD="$2" PGDATABASE="$3" \
        psql --host=127.0.0.1 --no-password --no-psqlrc \
        --set=ON_ERROR_STOP=1 --tuples-only --no-align --quiet --command "$4"
}

expect_value() {
    if [ "$1" != "$2" ]; then
        printf 'FALHA: %s\n' "$3" >&2
        exit 1
    fi
    printf 'OK: %s\n' "$3"
}

expect_denied() {
    if query "$1" "$2" "$3" 'SELECT 1' >/dev/null 2>&1; then
        printf 'FALHA: %s\n' "$4" >&2
        exit 1
    fi
    printf 'OK: %s\n' "$4"
}

expect_value "$(query customer_app "$CUSTOMER_DB_PASSWORD" customer_db \
    "SELECT current_user || ':' || current_database()")" \
    'customer_app:customer_db' 'customer_app autentica no próprio banco via TCP'
expect_value "$(query notification_app "$NOTIFICATION_DB_PASSWORD" notification_db \
    "SELECT current_user || ':' || current_database()")" \
    'notification_app:notification_db' 'notification_app autentica no próprio banco via TCP'

# A transação é revertida: o teste não mantém tabelas ou dados nos bancos.
probe_sql="BEGIN; CREATE TABLE public.datum_access_probe_$$ (id integer PRIMARY KEY); INSERT INTO public.datum_access_probe_$$ VALUES (1); SELECT count(*) FROM public.datum_access_probe_$$; ROLLBACK;"
expect_value "$(query customer_app "$CUSTOMER_DB_PASSWORD" customer_db "$probe_sql")" \
    '1' 'customer_app pode criar e escrever no próprio schema (rollback ao final)'
expect_value "$(query notification_app "$NOTIFICATION_DB_PASSWORD" notification_db "$probe_sql")" \
    '1' 'notification_app pode criar e escrever no próprio schema (rollback ao final)'

expect_value "$(query "$POSTGRES_USER" "$POSTGRES_PASSWORD" postgres \
    "SELECT count(*) FROM pg_roles WHERE rolname IN ('customer_app', 'notification_app') AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls")" \
    '2' 'os dois usuários de serviço não têm privilégios administrativos'

expect_denied customer_app "$CUSTOMER_DB_PASSWORD" notification_db \
    'customer_app não conecta em notification_db'
expect_denied notification_app "$NOTIFICATION_DB_PASSWORD" customer_db \
    'notification_app não conecta em customer_db'
expect_denied customer_app "$CUSTOMER_DB_PASSWORD" postgres \
    'customer_app não conecta no banco administrativo'
expect_denied notification_app "$NOTIFICATION_DB_PASSWORD" postgres \
    'notification_app não conecta no banco administrativo'
expect_denied customer_app "${CUSTOMER_DB_PASSWORD}_invalid" customer_db \
    'senha incorreta de customer_app é rejeitada'
expect_denied notification_app "${NOTIFICATION_DB_PASSWORD}_invalid" notification_db \
    'senha incorreta de notification_app é rejeitada'

printf 'PostgreSQL: validação de acesso e isolamento concluída.\n'
SH
