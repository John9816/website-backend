import datetime as dt
import os
from pathlib import Path

import pymysql


MYSQL = {
    "host": os.getenv("MYSQL_HOST", "localhost"),
    "port": int(os.getenv("MYSQL_PORT", "3306")),
    "user": os.getenv("MYSQL_USER", "root"),
    "password": os.getenv("MYSQL_PASSWORD", ""),
    "database": os.getenv("MYSQL_DATABASE", "website"),
    "charset": "utf8mb4",
    "cursorclass": pymysql.cursors.DictCursor,
    "connect_timeout": 15,
}

OUT = Path(__file__).resolve().parents[1] / ".wrangler" / "mysql-import.sql"
MAX_SQL_CHARS = 900_000


def q(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, (dt.datetime, dt.date, dt.time)):
        value = value.isoformat(sep=" ") if isinstance(value, dt.datetime) else value.isoformat()
    value = str(value)
    return "'" + value.replace("'", "''") + "'"


def fetch_all(cur, table):
    cur.execute(f"SELECT * FROM `{table}`")
    return list(cur.fetchall())


def table_exists(cur, table):
    cur.execute(
        "SELECT COUNT(*) AS c FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = %s",
        (table,),
    )
    return bool(cur.fetchone()["c"])


def cols_for(rows):
    seen = []
    for row in rows:
        for key in row:
            if key not in seen:
                seen.append(key)
    return seen


def insert_sql(table, rows, columns, conflict=None, update_columns=None):
    if not rows:
        return []
    result = []
    col_sql = ", ".join(columns)
    for row in rows:
        vals = ", ".join(q(row.get(col)) for col in columns)
        sql = f"INSERT INTO {table}({col_sql}) VALUES({vals})"
        if conflict:
            updates = update_columns if update_columns is not None else [c for c in columns if c != "id"]
            if updates:
                sql += f" ON CONFLICT({conflict}) DO UPDATE SET " + ", ".join(f"{c}=excluded.{c}" for c in updates)
            else:
                sql += f" ON CONFLICT({conflict}) DO NOTHING"
        else:
            sql += " ON CONFLICT(id) DO NOTHING"
        if len(sql) <= MAX_SQL_CHARS:
            result.append(sql + ";")
    return result


def project(row, mapping, defaults=None):
    defaults = defaults or {}
    out = {}
    for dst, src in mapping.items():
        out[dst] = row.get(src) if isinstance(src, str) else src(row)
    for key, val in defaults.items():
        out.setdefault(key, val)
    return out


def content_from_doc(row):
    return row.get("content_html") or row.get("content_json") or ""


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    counts = {}
    sql = ["PRAGMA foreign_keys = OFF;"]

    with pymysql.connect(**MYSQL) as conn:
        cur = conn.cursor()

        users = [
            project(r, {
                "id": "id",
                "username": "username",
                "password_hash": "password",
                "role": "role",
                "created_at": "created_at",
                "updated_at": "updated_at",
            })
            for r in fetch_all(cur, "user")
        ]
        sql += insert_sql("users", users, ["id", "username", "password_hash", "role", "created_at", "updated_at"], "id")
        counts["users"] = len(users)

        simple_tables = {
            "category": ["id", "user_id", "name", "icon", "sort_order", "created_at", "updated_at"],
            "nav_link": ["id", "user_id", "category_id", "name", "url", "description", "icon", "sort_order", "created_at", "updated_at"],
            "sys_config": ["config_key", "config_value", "description", "created_at", "updated_at"],
            "generated_image": ["id", "user_id", "prompt", "image_url", "image_data", "model", "size", "is_shared", "created_at"],
            "image_generation_task": ["id", "user_id", "type", "status", "prompt", "model", "size", "n", "result_json", "error_message", "created_at", "updated_at"],
            "ai_conversation": ["id", "user_id", "title", "model", "last_message_preview", "last_message_at", "created_at", "updated_at"],
            "ai_chat_message": ["id", "conversation_id", "role", "content", "model", "audio_model", "audio_source_url", "audio_data", "audio_mime_type", "audio_external_id", "finish_reason", "prompt_tokens", "completion_tokens", "total_tokens", "created_at"],
            "kb_space": ["id", "user_id", "name", "description", "sort_order", "created_at", "updated_at"],
            "kb_tag": ["id", "user_id", "name", "color", "created_at", "updated_at"],
            "kb_doc_tag": ["doc_id", "tag_id"],
            "kb_doc_share": ["id", "doc_id", "user_id", "token", "expires_at", "view_count", "created_at", "updated_at"],
            "music_play_history": ["id", "user_id", "source", "song_id", "name", "artist", "album", "cover_url", "duration_sec", "played_at", "created_at"],
            "music_favorite": ["id", "user_id", "source", "song_id", "name", "artist", "album", "cover_url", "duration_sec", "created_at", "updated_at"],
            "music_share": ["id", "user_id", "source", "song_id", "name", "artist", "album", "cover_url", "duration_sec", "requested_quality", "token", "expires_at", "view_count", "created_at", "updated_at"],
            "user_playlist_item": ["id", "playlist_id", "source", "song_id", "name", "artist", "album", "cover_url", "duration_sec", "sort_order", "created_at"],
        }

        for table, columns in simple_tables.items():
            if not table_exists(cur, table):
                counts[table] = 0
                continue
            rows = fetch_all(cur, table)
            if table == "image_generation_task":
                rows = [dict(r, type=r.get("type") or "generate") for r in rows]
            conflict = "config_key" if table == "sys_config" else ("id" if "id" in columns else ",".join(columns))
            update_columns = [c for c in columns if c not in ("id", "created_at")] if table == "sys_config" else None
            sql += insert_sql(table, rows, columns, conflict, update_columns)
            counts[table] = len(rows)

        if table_exists(cur, "kb_doc"):
            docs = [
                project(r, {
                    "id": "id",
                    "user_id": "user_id",
                    "space_id": "space_id",
                    "parent_id": "parent_id",
                    "title": "title",
                    "content": content_from_doc,
                    "status": "status",
                    "sort_order": "sort_order",
                    "created_at": "created_at",
                    "updated_at": "updated_at",
                })
                for r in fetch_all(cur, "kb_doc")
            ]
            sql += insert_sql("kb_doc", docs, ["id", "user_id", "space_id", "parent_id", "title", "content", "status", "sort_order", "created_at", "updated_at"], "id")
            counts["kb_doc"] = len(docs)

        if table_exists(cur, "kb_doc_version"):
            versions = [
                project(r, {
                    "id": "id",
                    "doc_id": "doc_id",
                    "title": "title",
                    "content": content_from_doc,
                    "created_at": "created_at",
                })
                for r in fetch_all(cur, "kb_doc_version")
            ]
            sql += insert_sql("kb_doc_version", versions, ["id", "doc_id", "title", "content", "created_at"], "id")
            counts["kb_doc_version"] = len(versions)

        if table_exists(cur, "user_playlist"):
            playlists = [
                project(r, {
                    "id": "id",
                    "user_id": "user_id",
                    "source": "source",
                    "external_id": "source_id",
                    "name": "name",
                    "cover_url": "cover_url",
                    "track_count": "track_count",
                    "source_url": "source_url",
                    "created_at": "created_at",
                    "updated_at": "updated_at",
                })
                for r in fetch_all(cur, "user_playlist")
            ]
            sql += insert_sql("user_playlist", playlists, ["id", "user_id", "source", "external_id", "name", "cover_url", "track_count", "source_url", "created_at", "updated_at"], "id")
            counts["user_playlist"] = len(playlists)

    sql += ["PRAGMA foreign_keys = ON;"]
    OUT.write_text("\n".join(sql) + "\n", encoding="utf-8")
    print(f"Wrote {OUT}")
    for table in sorted(counts):
        print(f"{table}\t{counts[table]}")


if __name__ == "__main__":
    main()
