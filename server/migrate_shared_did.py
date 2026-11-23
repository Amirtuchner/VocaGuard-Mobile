#!/opt/vocaguard-venv/bin/python3
"""One-off: move users who share a "dedicated" DID (swapped-params bug, 2026-11-21)
to DIDs of their own, and SMS each one the forwarding code for their new DID.
(Run with /opt/vocaguard-venv/bin/python3 — system python3 has no twilio module.)

Safe for users who ignore the SMS: _assign_dedicated_did records the old DID in
prev_did, so the old DID stays ambiguous (Diversion fallback, as before) instead of
routing their calls to its remaining owner. prev_did is only cleared once a call
arrives on the new DID AND their app has fetched the new DID (users.did_acked).

Usage (as root):
  /opt/vocaguard-venv/bin/python3 migrate_shared_did.py                 # dry run: who would move where, the SMS text
  migrate_shared_did.py --send          # assign + SMS
  migrate_shared_did.py --send --limit 1 [--only <phone>]   # try on one user first
"""
import argparse, sys, time, logging
sys.path.insert(0, "/opt/vocaguard")
import token_server as ts

LOG_FILE = "/var/log/vocaguard_did_migration.log"

SMS_TEXT = (
    "VocaGuard: כדי שנמשיך להגן על השיחות שלך, חייג/י פעם אחת מהטלפון: {code}\n"
    "To keep your calls protected, please dial this once from your phone: {code}"
)


def targets(conn, from_did, only=None):
    owner = (conn.execute("SELECT assigned_to FROM did_pool WHERE did_number=?",
                          (from_did,)).fetchone() or [None])[0]
    q = ("SELECT phone_number FROM users WHERE did_number=? AND phone_number != ? "
         "AND uninstalled_at IS NULL AND token_dead_at IS NULL")
    args = [from_did, owner or ""]
    if only:
        q += " AND phone_number=?"
        args.append(ts._normalize_phone(only))
    return owner, [r[0] for r in conn.execute(q + " ORDER BY created_at", args)]


def send_sms(to_digits, body):
    from twilio.rest import Client
    from_num = open("/opt/vocaguard/twilio_number.txt").read().strip()
    m = Client(ts.TWILIO_SID, ts.TWILIO_TOKEN).messages.create(
        to=f"+{to_digits}", from_=from_num, body=body)
    return m.sid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from-did", default="97233751369")
    ap.add_argument("--send", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--only")
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", force=True,
                        handlers=[logging.StreamHandler()] + ([logging.FileHandler(LOG_FILE)] if a.send else []))
    log = logging.getLogger("migrate")

    conn = ts.db_connect()
    owner, users = targets(conn, a.from_did, a.only)
    if a.limit:
        users = users[:a.limit]
    quiet = time.time() - ts.DID_QUARANTINE_DAYS * 86400
    free = [r[0] for r in conn.execute(
        "SELECT did_number FROM did_pool WHERE assigned_to IS NULL "
        "AND (last_call_at IS NULL OR last_call_at < ?) "
        "ORDER BY last_call_at IS NOT NULL, last_call_at, did_number", (quiet,))]
    log.info("DID %s: owner kept=%s, %d user(s) to move, %d free DID(s)%s",
             a.from_did, owner, len(users), len(free), "" if a.send else " — DRY RUN")
    if len(free) < len(users):
        log.warning("not enough free DIDs: %d short — the rest stay put", len(users) - len(free))

    for i, phone in enumerate(users):
        if not a.send:
            if i < len(free):
                log.info("would move %s -> %s, SMS code *21*+%s#", phone, free[i], free[i])
            continue
        new_did = ts._assign_dedicated_did(conn, phone)
        if not new_did:
            log.warning("pool empty — stopping before %s", phone)
            break
        conn.commit()   # commit the move BEFORE texting the code for it
        code = f"*21*+{new_did}#"
        try:
            sid = send_sms(phone, SMS_TEXT.format(code=code))
            log.info("moved %s %s -> %s, SMS sid=%s", phone, a.from_did, new_did, sid)
        except Exception as e:
            log.error("moved %s -> %s but SMS FAILED: %s", phone, new_did, e)
    if not a.send:
        print("\nSMS text:\n" + SMS_TEXT.format(code="*21*+97233751XXX#"))
    conn.close()


if __name__ == "__main__":
    main()
