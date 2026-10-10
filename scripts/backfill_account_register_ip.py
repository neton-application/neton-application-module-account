#!/usr/bin/env python3
"""One-time backfill of missing account_users.register_ip values.

Only rows with an empty register_ip and a non-empty login_ip are eligible.
The script is dry-run by default; pass --apply to commit the transaction.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tomllib
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import unquote, urlparse


ELIGIBLE_WHERE = """
NULLIF(BTRIM(register_ip), '') IS NULL
AND NULLIF(BTRIM(login_ip), '') IS NOT NULL
""".strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=Path("config/database.conf"),
        help="Neton database TOML file (default: config/database.conf)",
    )
    parser.add_argument(
        "--database-url",
        help="Override database URL; otherwise NETON_DATABASE__URI or --config is used",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Commit the backfill. Without this flag the script only reports counts.",
    )
    parser.add_argument(
        "--backup",
        type=Path,
        help="CSV backup path for eligible rows (used with --apply)",
    )
    return parser.parse_args()


def load_database_url(args: argparse.Namespace) -> str:
    if args.database_url:
        return args.database_url
    if value := os.environ.get("NETON_DATABASE__URI"):
        return value
    with args.config.open("rb") as handle:
        config = tomllib.load(handle)
    try:
        return str(config["default"]["uri"])
    except KeyError as exc:
        raise SystemExit(f"missing [default].uri in {args.config}") from exc


def psql_command(database_url: str) -> tuple[list[str], dict[str, str]]:
    parsed = urlparse(database_url)
    if parsed.scheme not in {"postgres", "postgresql"}:
        raise SystemExit(f"unsupported database URL scheme: {parsed.scheme!r}")

    database = parsed.path.lstrip("/")
    if not parsed.hostname or not parsed.username or not database:
        raise SystemExit("database URL must include host, username, and database")

    command = [
        "psql",
        "--no-psqlrc",
        "--set",
        "ON_ERROR_STOP=1",
        "--tuples-only",
        "--no-align",
        "--host",
        parsed.hostname,
        "--port",
        str(parsed.port or 5432),
        "--username",
        unquote(parsed.username),
        "--dbname",
        unquote(database),
    ]
    env = os.environ.copy()
    if parsed.password:
        env["PGPASSWORD"] = unquote(parsed.password)
    return command, env


def run_sql(command: list[str], env: dict[str, str], sql: str) -> str:
    result = subprocess.run(
        [*command, "--command", sql],
        check=True,
        env=env,
        text=True,
        capture_output=True,
    )
    return result.stdout.strip()


def eligible_count(command: list[str], env: dict[str, str]) -> int:
    output = run_sql(
        command,
        env,
        f"SELECT COUNT(*) FROM account_users WHERE {ELIGIBLE_WHERE};",
    )
    return int(output)


def backup_eligible_rows(
    command: list[str],
    env: dict[str, str],
    requested_path: Path | None,
) -> Path:
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    path = requested_path or Path(f"account_register_ip_backfill_{timestamp}.csv")
    path.parent.mkdir(parents=True, exist_ok=True)
    sql = f"""
COPY (
    SELECT id, register_ip, login_ip
      FROM account_users
     WHERE {ELIGIBLE_WHERE}
     ORDER BY id
) TO STDOUT WITH (FORMAT CSV, HEADER TRUE);
"""
    path.write_text(run_sql(command, env, sql) + "\n", encoding="utf-8")
    return path


def apply_backfill(command: list[str], env: dict[str, str]) -> int:
    sql = f"""
BEGIN;
WITH updated AS (
    UPDATE account_users
       SET register_ip = BTRIM(login_ip)
     WHERE {ELIGIBLE_WHERE}
     RETURNING id
)
SELECT COUNT(*) FROM updated;
COMMIT;
"""
    lines = [line for line in run_sql(command, env, sql).splitlines() if line.isdigit()]
    if not lines:
        raise RuntimeError("psql did not return the updated row count")
    return int(lines[-1])


def main() -> int:
    args = parse_args()
    command, env = psql_command(load_database_url(args))
    before = eligible_count(command, env)
    print(f"eligible_rows={before}")

    if not args.apply:
        print("dry_run=true; no rows changed (pass --apply to commit)")
        return 0

    backup_path = backup_eligible_rows(command, env, args.backup)
    print(f"backup={backup_path}")
    updated = apply_backfill(command, env)
    remaining = eligible_count(command, env)
    print(f"updated_rows={updated}")
    print(f"remaining_eligible_rows={remaining}")
    if updated != before or remaining != 0:
        print("verification failed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
