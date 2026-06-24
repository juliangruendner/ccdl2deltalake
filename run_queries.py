#!/usr/bin/env python3
"""Execute all .sql files in a folder against Trino and print name, duration, and count."""

import argparse
import glob
import os
import time

import urllib3
import trino
from trino.auth import OAuth2Authentication, ConsoleRedirectHandler


def main():
    parser = argparse.ArgumentParser(description="Run SQL files against Trino and report counts.")
    parser.add_argument("folder", help="Folder containing .sql files")
    parser.add_argument("--host", default="localhost", help="Trino host (default: localhost)")
    parser.add_argument("--port", type=int, default=8080, help="Trino port (default: 8080)")
    parser.add_argument("--user", default="trino", help="Trino user (default: trino)")
    parser.add_argument("--catalog", default="fhir", help="Trino catalog (default: fhir)")
    parser.add_argument("--schema", default="default", help="Trino schema (default: default)")
    parser.add_argument("--oauth", action="store_true", help="Use OAuth 2.0 authentication (implies --https)")
    parser.add_argument("--https", action="store_true", help="Use HTTPS")
    parser.add_argument("--no-verify-ssl", action="store_true", help="Disable SSL certificate verification")
    args = parser.parse_args()

    sql_files = sorted(glob.glob(os.path.join(args.folder, "*.sql")))
    if not sql_files:
        print(f"No .sql files found in {args.folder}")
        return

    if args.no_verify_ssl:
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

    use_https = args.https or args.oauth
    conn = trino.dbapi.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        catalog=args.catalog,
        schema=args.schema,
        http_scheme="https" if use_https else "http",
        auth=OAuth2Authentication(redirect_auth_url_handler=ConsoleRedirectHandler()) if args.oauth else None,
        verify=not args.no_verify_ssl,
    )

    name_width = max(len(os.path.splitext(os.path.basename(f))[0]) for f in sql_files)
    header = f"{'Query':<{name_width}}  {'Time (s)':>10}  {'Count':>10}"
    print(header)
    print("-" * len(header))

    for path in sql_files:
        name = os.path.splitext(os.path.basename(path))[0]
        with open(path) as f:
            sql = f.read().strip()

        cursor = conn.cursor()
        try:
            start = time.perf_counter()
            cursor.execute(sql)
            row = cursor.fetchone()
            elapsed = time.perf_counter() - start
            if row is None:
                count = 0
            elif cursor.description[0][0] == "patient_count":
                count = row[0]
            else:
                # raw patient_id query — count all rows
                count = 1 + sum(1 for _ in cursor)
            print(f"{name:<{name_width}}  {elapsed:>10.2f}  {count:>10}")
        except Exception as e:
            print(f"{name:<{name_width}}  {'ERROR':>10}  {str(e)[:60]}")
        finally:
            cursor.close()

    conn.close()


if __name__ == "__main__":
    main()
