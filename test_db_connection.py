import pymysql
import os
import sys

HOST = os.getenv("MYSQL_HOST", "localhost")
PORT = int(os.getenv("MYSQL_PORT", "3306"))
USER = os.getenv("MYSQL_USER", "root")
PASSWORD = os.getenv("MYSQL_PASSWORD", "")
DB_NAME = os.getenv("MYSQL_DATABASE", "website")

def main():
    # 1) connect without DB, check server + version
    try:
        conn = pymysql.connect(
            host=HOST, port=PORT, user=USER, password=PASSWORD,
            connect_timeout=10, charset="utf8mb4"
        )
    except Exception as e:
        print(f"[FAIL] cannot connect: {e}")
        sys.exit(1)

    with conn.cursor() as cur:
        cur.execute("SELECT VERSION(), NOW(), @@character_set_server, @@time_zone")
        ver, now, cs, tz = cur.fetchone()
        print(f"[OK] connected | version={ver} now={now} charset={cs} tz={tz}")

        # 2) ensure database
        cur.execute(
            f"CREATE DATABASE IF NOT EXISTS `{DB_NAME}` "
            "DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci"
        )
        print(f"[OK] database {DB_NAME!r} ensured")

        # 3) list databases (sanity check)
        cur.execute("SHOW DATABASES")
        dbs = [r[0] for r in cur.fetchall()]
        print(f"[OK] databases visible: {dbs}")

        # 4) switch to website and list tables
        cur.execute(f"USE `{DB_NAME}`")
        cur.execute("SHOW TABLES")
        tables = [r[0] for r in cur.fetchall()]
        print(f"[OK] tables in {DB_NAME!r}: {tables if tables else '(empty - Spring will auto-create on first boot)'}")

        # 5) write/read smoke test in a temp table, then drop
        cur.execute("CREATE TABLE IF NOT EXISTS _conn_probe (id INT PRIMARY KEY, msg VARCHAR(32))")
        cur.execute("INSERT INTO _conn_probe (id, msg) VALUES (1, 'ping') "
                    "ON DUPLICATE KEY UPDATE msg=VALUES(msg)")
        conn.commit()
        cur.execute("SELECT msg FROM _conn_probe WHERE id=1")
        row = cur.fetchone()
        print(f"[OK] write/read roundtrip: {row[0]!r}")
        cur.execute("DROP TABLE _conn_probe")
        conn.commit()
        print("[OK] cleanup done")

    conn.close()
    print("\nAll checks passed.")

if __name__ == "__main__":
    main()
