#!/opt/vocaguard-venv/bin/python3
"""Lightweight pre-answer AGI: sends FCM incoming_call alert immediately.
   Includes the Asterisk channel name so the app can signal Accept via AMI.
   Also clears any stale PJSIP/vocaguard-* channels so the bridge can succeed.
   Reads ORIGINAL_CALLED (from REDIRECTING(from-num)) to identify which user
   the call was originally for, then looks up their FCM token from the DB.
"""
import sys, os, json, subprocess, re, sqlite3, time
import firebase_admin
from firebase_admin import credentials, messaging

SERVICE_ACCOUNT = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE  = "/opt/vocaguard/fcm_token.txt"   # single-user fallback
DB_PATH         = "/opt/vocaguard/data/users.db"   # root:asterisk 0660 in a 2770 dir so AGI helpers (asterisk user) can write
NOTIFY_LOG      = "/var/log/vocaguard_notify.log"


def _log(msg: str):
    try:
        with open(NOTIFY_LOG, "a") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")
    except Exception:
        pass


def agi_init():
    agi_vars = {}
    while True:
        line = sys.stdin.readline()
        if line.strip() == "":
            break
        if ":" in line:
            key, _, value = line.partition(":")
            agi_vars[key.strip()] = value.strip()
    return agi_vars


def agi_get_variable(name: str) -> str:
    """Read a channel variable via the AGI GET VARIABLE command."""
    sys.stdout.write(f"GET VARIABLE {name}\n")
    sys.stdout.flush()
    response = sys.stdin.readline().strip()
    match = re.search(r'\((.+)\)', response)
    return match.group(1) if match else ""


def agi_set_variable(name: str, value: str):
    """Set a channel variable via the AGI SET VARIABLE command."""
    sys.stdout.write(f'SET VARIABLE {name} "{value}"\n')
    sys.stdout.flush()
    sys.stdin.readline()  # consume the AGI result line


def get_fcm_token_for_number(phone_number: str) -> str:
    """Look up FCM token by original called number (suffix match).
    DIDWW strips the country code prefix, so we match on trailing digits.
    Falls back to single-user token file.
    """
    # Digits only: a "+1..." or "tel:" style Diversion number would otherwise never
    # suffix-match the stored digits-only phone. Require 8+ digits so a short or
    # garbled value can't LIKE-match some other user's number.
    phone_number = re.sub(r"[^0-9]", "", phone_number or "")
    if len(phone_number) < 8:
        phone_number = ""
    if phone_number:
        try:
            conn = sqlite3.connect(DB_PATH, timeout=30)
            row = conn.execute(
                "SELECT fcm_token FROM users WHERE phone_number LIKE ?",
                (f"%{phone_number}",)
            ).fetchone()
            conn.close()
            if row and row[0]:
                _log(f"token source=DB for '{phone_number}' tail={row[0][-10:]}")
                return row[0]
            _log(f"token source=DB MISS for '{phone_number}' -> using fallback file")
        except Exception as e:
            sys.stdout.write(f'VERBOSE "DB lookup warning: {e}" 1\n')
            sys.stdout.flush()
            _log(f"token DB error: {e} -> using fallback file")
    else:
        _log("token: EMPTY diversion_number -> using fallback file")
    # Fallback: single-user token file
    try:
        t = open(FCM_TOKEN_FILE).read().strip()
        _log(f"token source=FALLBACK_FILE tail={t[-10:]}")
        return t
    except Exception:
        _log("token: no fallback file, returning EMPTY")
        return ""


def cleanup_stale_vocaguard_channels():
    """Hang up any lingering PJSIP/vocaguard_*-* channels before bridging a new call.

    Uses the Asterisk CLI (not AMI) — CoreShowChannels was silently returning
    "Permission denied" for the vocaguard-bridge AMI user regardless of granted
    read classes, so this safety net was never actually running. The AGI process
    runs as the asterisk OS user, which owns asterisk.ctl, so `asterisk -rx` works.
    """
    try:
        result = subprocess.run(
            ["asterisk", "-rx", "core show channels concise"],
            capture_output=True, text=True, timeout=5,
        )

        stale = []
        for line in result.stdout.splitlines():
            fields = line.split("!")
            if len(fields) < 5:
                continue
            channel, state = fields[0], fields[4]
            if not channel.startswith("PJSIP/vocaguard"):
                continue
            # Never kill a channel that is Up (actively bridged)
            if state == "Up":
                sys.stdout.write(f'VERBOSE "Skipping active channel {channel}" 1\n')
                sys.stdout.flush()
                continue
            stale.append(channel)

        for ch in stale:
            subprocess.run(
                ["asterisk", "-rx", f"channel request hangup {ch}"],
                capture_output=True, timeout=5,
            )

        if stale:
            sys.stdout.write(f'VERBOSE "Cleared {len(stale)} stale channel(s)" 1\n')
            sys.stdout.flush()
    except Exception as e:
        sys.stdout.write(f'VERBOSE "cleanup_stale warning: {e}" 1\n')
        sys.stdout.flush()


DEDUP_TTL = 120  # seconds — ignore duplicate FCMs for same caller within this window
DEDUP_DIR  = "/tmp/vg_dedup"


def _dedup_key(caller: str) -> str:
    import hashlib
    return os.path.join(DEDUP_DIR, "caller_" + hashlib.md5(caller.encode()).hexdigest())


def _is_duplicate(caller: str) -> bool:
    """Return True if this caller already triggered an FCM within DEDUP_TTL seconds."""
    if not caller:
        return False
    try:
        os.makedirs(DEDUP_DIR, exist_ok=True)
        key = _dedup_key(caller)
        if os.path.exists(key):
            age = time.time() - os.path.getmtime(key)
            if age < DEDUP_TTL:
                return True
        # Touch the file to mark this caller as recently notified
        open(key, "w").close()
    except Exception:
        pass
    return False


def get_token_by_did(called_did: str) -> str:
    """FCM token for the user whose DEDICATED DID was called ('+'/spaces/digits
    normalized). Returns '' unless exactly one user owns that DID. This is the robust
    multi-tenant key: the called number is always in the INVITE, unlike the Diversion
    header which some carriers strip entirely (2026-11-10)."""
    d = re.sub(r"[^0-9]", "", called_did or "")
    if not d:
        return ""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=30)
        # Claimants = current owner(s) + anyone just moved OFF this DID whose handset
        # may still forward here (prev_did). Route only when there is exactly one.
        rows = conn.execute(
            "SELECT phone_number, fcm_token, "
            "replace(replace(did_number,'+',''),' ','') = ?, COALESCE(did_acked,'') = ? FROM users "
            "WHERE replace(replace(did_number,'+',''),' ','') = ? "
            "OR replace(replace(prev_did,'+',''),' ','') = ?", (d, d, d, d)
        ).fetchall()
        token, clear_prev_for = "", None
        if len(rows) == 1 and rows[0][1]:
            phone, token, is_current, app_knows = rows[0]
            if is_current and app_knows:
                # A call arrived on their NEW DID (handset re-forwarded) AND their app
                # has the new DID (so it won't re-dial *21* to the old one) → the old
                # DID no longer needs to count them as a claimant.
                clear_prev_for = phone
            _log(f"token source=DEDICATED_DID did={d} tail={token[-10:]}")
        elif len(rows) > 1:
            _log(f"did={d} has {len(rows)} claimants -> Diversion fallback")
        # Bookkeeping writes are best-effort and must never cost us the routing
        # result above (e.g. DB not writable by the asterisk user).
        try:
            # Activity stamp: a released DID is only re-assigned after a quiet
            # period, so stale forwarding can't reach a new owner.
            conn.execute("UPDATE did_pool SET last_call_at=? WHERE did_number=?", (time.time(), d))
            if clear_prev_for:
                conn.execute("UPDATE users SET prev_did=NULL WHERE phone_number=? AND prev_did IS NOT NULL",
                             (clear_prev_for,))
            conn.commit()
        except Exception as e:
            _log(f"did bookkeeping write failed: {e}")
        conn.close()
        return token
    except Exception as e:
        _log(f"did lookup error: {e}")
    return ""


def _send_uninstall_sms(phone_digits: str):
    """One-time Twilio SMS telling the user their forwarding may still be live.
    Soft wording: an UNREGISTERED token USUALLY means uninstall, but can also be
    a stale token on an installed app (the app re-registers on token rotation,
    which clears the flag server-side)."""
    try:
        sid      = open("/opt/vocaguard/twilio_sid.txt").read().strip()
        tok      = open("/opt/vocaguard/twilio_token.txt").read().strip()
        from_num = open("/opt/vocaguard/twilio_number.txt").read().strip()
        from twilio.rest import Client
        body = ("VocaGuard: it looks like the app was removed from your phone, but "
                "call forwarding may still be active. To cancel it, dial ##21# from "
                "your phone. If you still use VocaGuard, just open the app.")
        sms = Client(sid, tok).messages.create(to=f"+{phone_digits}", from_=from_num, body=body)
        _log(f"uninstall SMS sent to +{phone_digits} sid={sms.sid}")
    except Exception as e:
        _log(f"uninstall SMS error: {e}")


def _handle_dead_token(token: str):
    """FCM bounced UNREGISTERED → the app was uninstalled (or its token revoked).
    Mark the user (uninstalled_at) and SMS them ONCE about stale *21* forwarding.
    /register and /register-token clear the flag, so a reinstall or an in-place
    token rotation re-arms the notification for a future real uninstall."""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=30)
        cols = [r[1] for r in conn.execute("PRAGMA table_info(users)").fetchall()]
        if "uninstalled_at" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN uninstalled_at REAL")
            conn.commit()
        row = conn.execute(
            "SELECT phone_number, uninstalled_at FROM users WHERE fcm_token=?",
            (token,)
        ).fetchone()
        if row and not row[1]:
            conn.execute(
                "UPDATE users SET uninstalled_at=? WHERE fcm_token=?",
                (time.time(), token)
            )
            conn.commit()
            conn.close()
            _send_uninstall_sms(row[0])
        else:
            conn.close()
    except Exception as e:
        _log(f"dead-token handling error: {e}")


def _record_call_channel(channel: str, token: str):
    """Remember who this call was pushed to: token_server only lets that user
    accept or hang up the channel (security audit 2026-11-23, finding 5)."""
    if not channel:
        return
    try:
        conn = sqlite3.connect(DB_PATH, timeout=30)
        row = conn.execute("SELECT phone_number FROM users WHERE fcm_token=?", (token,)).fetchone()
        conn.execute("DELETE FROM call_channels WHERE created_at < ?", (time.time() - 86400,))
        conn.execute(
            "INSERT OR REPLACE INTO call_channels (channel, phone_number, fcm_token, created_at) "
            "VALUES (?, ?, ?, ?)", (channel, row[0] if row else None, token, time.time()))
        conn.commit()
        conn.close()
    except Exception as e:
        _log(f"call_channels write failed: {e}")


def send_fcm(diversion_number: str, data: dict, called_did: str = "") -> str:
    """Send the push. Returns 'sent', 'dead_token' (UNREGISTERED — app gone),
    'no_token' or 'error' so main() can fail-fast the dialplan."""
    try:
        cred = credentials.Certificate(SERVICE_ACCOUNT)
        firebase_admin.initialize_app(cred)

        # Prefer the dedicated-DID tenant key; fall back to the Diversion header.
        token = get_token_by_did(called_did) or get_fcm_token_for_number(diversion_number)
        if not token:
            _log("FCM NOT sent: no token")
            return "no_token"
        if diversion_number:
            data["diversion_number"] = diversion_number
        msg = messaging.Message(
            data=data,
            android=messaging.AndroidConfig(priority="high"),
            token=token,
        )
        try:
            r = messaging.send(msg)
            _log(f"FCM sent type={data.get('type')} tail={token[-10:]} id={r}")
            if data.get("type") == "incoming_call":
                _record_call_channel(data.get("asterisk_channel", ""), token)
            return "sent"
        except messaging.UnregisteredError:
            _log(f"FCM UNREGISTERED tail={token[-10:]} — app likely uninstalled")
            _handle_dead_token(token)
            return "dead_token"
    except Exception as e:
        sys.stdout.write(f'VERBOSE "FCM notify error: {e}" 1\n')
        sys.stdout.flush()
        _log(f"FCM send error: {e}")
        return "error"


def main():
    agi_vars = agi_init()
    caller  = agi_vars.get("agi_callerid", "")
    channel = agi_vars.get("agi_channel", "")
    # "cancel" mode: invoked from the h extension when the caller leg hung up
    # before the user accepted — tells the app to stop ringing.
    mode = (sys.argv[1] if len(sys.argv) > 1 else agi_vars.get("agi_arg_1", "")).strip()

    # Read original called number (set in extensions.conf from REDIRECTING(from-num))
    diversion_number = agi_get_variable("ORIGINAL_CALLED")
    # The DID that was actually dialed (always present) — the robust tenant key for
    # users on a dedicated DID. Falls back to the Diversion header for shared-DID users.
    called_did = agi_vars.get("agi_extension", "")
    _log(f"CALL mode='{mode}' caller='{caller}' channel='{channel}' called_did='{called_did}' ORIGINAL_CALLED='{diversion_number}'")

    if mode == "cancel":
        send_fcm(diversion_number, {
            "type":             "call_cancelled",
            "caller_number":    caller,
            "asterisk_channel": channel,
        }, called_did=called_did)
        # Clear the dedup marker so the same caller ringing back within
        # DEDUP_TTL gets a fresh incoming_call FCM instead of silence.
        try:
            os.remove(_dedup_key(caller))
        except OSError:
            pass
        return

    # Deduplicate: if same caller was notified recently, skip FCM (DIDWW/Twilio retry)
    if _is_duplicate(caller):
        sys.stdout.write(f'VERBOSE "Duplicate FCM suppressed for {caller}" 1\n')
        sys.stdout.flush()
        return

    cleanup_stale_vocaguard_channels()

    status = send_fcm(diversion_number, {
        "type":             "incoming_call",
        "caller_number":    caller,
        "asterisk_channel": channel,
    }, called_did=called_did)

    if status == "dead_token":
        # App is gone — nobody can ever accept. Tell the dialplan to fail fast
        # (answer briefly + hang up) instead of fake-ringing the caller for 60s;
        # the short answered call makes the carrier drop the stale *21* rule.
        agi_set_variable("VG_DEAD_TOKEN", "1")
        # Clear the dedup marker so retries/next calls re-detect and fail fast too.
        try:
            os.remove(_dedup_key(caller))
        except OSError:
            pass


if __name__ == "__main__":
    main()
