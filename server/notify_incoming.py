#!/opt/vocaguard-venv/bin/python3
"""Lightweight pre-answer AGI: sends FCM incoming_call alert immediately.
   Includes the Asterisk channel name so the app can signal Accept via AMI.
"""
import sys, os, json
import firebase_admin
from firebase_admin import credentials, messaging

SERVICE_ACCOUNT = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE  = "/opt/vocaguard/fcm_token.txt"

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

def main():
    agi_vars = agi_init()
    caller  = agi_vars.get("agi_callerid", "")
    channel = agi_vars.get("agi_channel", "")
    try:
        cred = credentials.Certificate(SERVICE_ACCOUNT)
        firebase_admin.initialize_app(cred)
        token = open(FCM_TOKEN_FILE).read().strip()
        if token:
            msg = messaging.Message(
                data={
                    "type":             "incoming_call",
                    "caller_number":    caller,
                    "asterisk_channel": channel,
                },
                android=messaging.AndroidConfig(priority="high"),
                token=token,
            )
            messaging.send(msg)
    except Exception as e:
        sys.stdout.write(f'VERBOSE "FCM notify error: {e}" 1\n')
        sys.stdout.flush()

if __name__ == "__main__":
    main()
