#!/opt/vocaguard-venv/bin/python3
"""Daily DID-pool maintenance (run by vocaguard-did-maintenance.timer).

1. Dead-token check: FCM dry-run to every user's token. UNREGISTERED → token_dead_at.
   (Separate from uninstalled_at, which gates the call-time "cancel forwarding" SMS.)
2. Reclaim: a dedicated DID whose owner has been gone (uninstalled_at / token_dead_at)
   for RECLAIM_DAYS goes back to the pool. token_server only re-assigns it after
   DID_QUARANTINE_DAYS with no forwarded calls, so stale *21* can't ring a new owner.
3. Low-pool alert: SMS the admin when assignable DIDs < LOW_POOL_THRESHOLD, or when
   users who need a dedicated DID are stuck without one.

Usage: did_maintenance.py [--dry-run]   (--dry-run: report only, change nothing, no SMS)
"""
import sys, time, sqlite3, logging

DB_PATH             = "/opt/vocaguard/data/users.db"   # root:asterisk 0660 in a 2770 dir so AGI helpers (asterisk user) can write
SERVICE_ACCOUNT     = "/opt/vocaguard/service-account.json"
ADMIN_PHONE_FILE    = "/opt/vocaguard/admin_phone.txt"   # E.164, e.g. +9725XXXXXXXX
LOG_FILE            = "/var/log/vocaguard_did_maintenance.log"
RECLAIM_DAYS        = 14
DID_QUARANTINE_DAYS = 30   # keep in sync with token_server.DID_QUARANTINE_DAYS
LOW_POOL_THRESHOLD  = 5

DRY = "--dry-run" in sys.argv
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
    handlers=[logging.StreamHandler()] + ([] if DRY else [logging.FileHandler(LOG_FILE)]),
)
log = logging.getLogger("did_maint")


def check_dead_tokens(conn) -> int:
    import firebase_admin
    from firebase_admin import credentials, messaging
    firebase_admin.initialize_app(credentials.Certificate(SERVICE_ACCOUNT))
    dead = 0
    for phone, token, dead_at in conn.execute(
            "SELECT phone_number, fcm_token, token_dead_at FROM users WHERE fcm_token != ''").fetchall():
        try:
            messaging.send(messaging.Message(data={"type": "ping"}, token=token), dry_run=True)
            if dead_at:  # alive again without re-registering (rare) → clear
                conn.execute("UPDATE users SET token_dead_at=NULL WHERE phone_number=?", (phone,))
        except messaging.UnregisteredError:
            dead += 1
            if not dead_at:
                log.info("token dead: %s", phone)
                conn.execute("UPDATE users SET token_dead_at=? WHERE phone_number=?", (time.time(), phone))
        except Exception as e:
            log.warning("dry-run failed for %s: %s (left unchanged)", phone, e)
    return dead


def reclaim(conn) -> list:
    cutoff = time.time() - RECLAIM_DAYS * 86400
    rows = conn.execute(
        "SELECT u.phone_number, u.did_number FROM users u "
        "JOIN did_pool p ON p.did_number = u.did_number AND p.assigned_to = u.phone_number "
        "WHERE COALESCE(u.uninstalled_at, u.token_dead_at) < ?", (cutoff,)
    ).fetchall()
    for phone, did in rows:
        log.info("reclaim DID %s from %s (gone > %dd)", did, phone, RECLAIM_DAYS)
        conn.execute("UPDATE did_pool SET assigned_to=NULL, released_at=? WHERE did_number=?",
                     (time.time(), did))
        conn.execute("UPDATE users SET did_number=NULL WHERE phone_number=?", (phone,))
    return rows


def pool_status(conn) -> dict:
    quiet_since = time.time() - DID_QUARANTINE_DAYS * 86400
    q = lambda s, *a: conn.execute(s, a).fetchone()[0]
    return {
        "total":       q("SELECT COUNT(*) FROM did_pool"),
        "assigned":    q("SELECT COUNT(*) FROM did_pool WHERE assigned_to IS NOT NULL"),
        "available":   q("SELECT COUNT(*) FROM did_pool WHERE assigned_to IS NULL "
                         "AND (last_call_at IS NULL OR last_call_at < ?)", quiet_since),
        "quarantined": q("SELECT COUNT(*) FROM did_pool WHERE assigned_to IS NULL "
                         "AND last_call_at >= ?", quiet_since),
        # Users who need their own DID but don't have one: non-Diversion carrier on the
        # shared DID, or still sharing a DID the pool says belongs to someone else.
        "waiting":     q("SELECT COUNT(*) FROM users u WHERE u.uninstalled_at IS NULL "
                         "AND u.token_dead_at IS NULL AND ("
                         "(u.did_number IS NULL AND u.carrier_safe = 0) OR "
                         "EXISTS (SELECT 1 FROM did_pool p WHERE p.did_number = u.did_number "
                         "AND COALESCE(p.assigned_to,'') != u.phone_number))"),
    }


def send_admin_sms(body: str):
    try:
        admin = open(ADMIN_PHONE_FILE).read().strip()
    except OSError:
        log.warning("no %s — alert not sent: %s", ADMIN_PHONE_FILE, body)
        return
    try:
        from twilio.rest import Client
        sid      = open("/opt/vocaguard/twilio_sid.txt").read().strip()
        tok      = open("/opt/vocaguard/twilio_token.txt").read().strip()
        from_num = open("/opt/vocaguard/twilio_number.txt").read().strip()
        m = Client(sid, tok).messages.create(to=admin, from_=from_num, body=body)
        log.info("admin SMS sent sid=%s", m.sid)
    except Exception as e:
        log.error("admin SMS failed: %s", e)


def main():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    dead = check_dead_tokens(conn)
    reclaimed = reclaim(conn)
    st = pool_status(conn)
    log.info("dead_tokens=%d reclaimed=%d pool=%s%s", dead, len(reclaimed), st, " (DRY RUN)" if DRY else "")
    if DRY:
        conn.rollback()
        conn.close()
        return
    conn.commit()
    conn.close()
    if st["available"] < LOW_POOL_THRESHOLD or st["waiting"] > 0:
        send_admin_sms(
            f"VocaGuard DIDs: {st['available']} free, {st['waiting']} user(s) waiting for "
            f"a DID, {st['quarantined']} in quarantine, {st['assigned']}/{st['total']} assigned. "
            f"Buy DIDs in DIDWW and add them to did_pool."
        )


if __name__ == "__main__":
    main()
