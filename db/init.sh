#!/bin/bash
# Runs once when the Postgres volume is first created.
# Applies schema.sql to the app database and to a separate database for tests.
set -e

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /schema/schema.sql
createdb -U "$POSTGRES_USER" stara_test
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d stara_test -f /schema/schema.sql
