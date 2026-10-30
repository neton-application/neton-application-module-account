# account SQL Baseline

Release: 1.0.0-beta. PostgreSQL only.

- `postgresql/V001__schema.sql`: final tables, indexes, constraints and other schema objects.
- `postgresql/V002__data.sql`: initial configuration, dictionaries, menus, permissions and sequence state.
- `baseline.json`: immutable source provenance and checksums for this release.

This baseline is for a fresh database. Rebuild pre-beta test databases; do not
clear only the migration ledger, bypass checksum errors or apply these files
over an existing installation. No production database is reset by these files.
Use the distribution's normal migration command and its module dependency order.
The SQL is embedded at build time: rebuild the application before migration.

Menu IDs remain database-generated. Parent references use stable menu attributes
and role grants resolve role codes. Do not replace them with hard-coded IDs.

After publishing this baseline, never edit V001/V002. Future changes start at
V003 and remain versioned migrations. This consolidation is a one-time pre-beta
reset, not a replacement for migration history during normal upgrades.
