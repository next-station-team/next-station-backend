#!/usr/bin/env python3
"""CI-only real Linux systemd installation test. Uses an isolated service and no real push endpoint."""
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

SERVICE = 'nextstation-ci'
BASE = 'http://127.0.0.1:18081'
ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / 'scripts/deploy.py'
JAR = ROOT / 'build/libs/backend-0.0.1-SNAPSHOT.jar'


def run(*args):
    subprocess.run(args, check=True, cwd=ROOT)


def api(path, method='GET', body=None, device=None):
    headers = {}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if device:
        headers['X-Device-Id'] = device
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)


def wait():
    for _ in range(45):
        try:
            if api('/api/health') == {'status': 'UP'}:
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError('Restart readiness timed out')


try:
    run('sudo', sys.executable, str(DEPLOY), 'install', '--jar', str(JAR), '--service-name', SERVICE,
        '--port', '18081', '--frontend-origin', 'https://frontend.example', '--vapid-subject', 'mailto:ci@example.com')
    first_key = api('/api/push/vapid-public-key')['publicKey']
    device = str(uuid.uuid4())
    session = str(uuid.uuid4())
    api('/api/push/subscriptions', 'POST', {
        'deviceId': device, 'endpoint': 'https://fcm.googleapis.com/wp/ci-' + device,
        'keys': {'p256dh': first_key, 'auth': 'AAAAAAAAAAAAAAAAAAAAAA'}})
    # One hour in the future: no external Web Push is sent by this smoke test.
    arrival = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
    path = f'/api/sessions/{session}/alarms'
    reserved = api(path, 'PUT', {'arrivalAt': arrival, 'reminderMinutes': 5}, device)
    assert len(reserved) == 2
    run('sudo', 'systemctl', 'restart', SERVICE)
    wait()
    assert api(path, device=device) == reserved
    assert api('/api/push/vapid-public-key')['publicKey'] == first_key
    # Updating without key arguments preserves the key/database and changes only the requested origin.
    run('sudo', sys.executable, str(DEPLOY), 'install', '--jar', str(JAR), '--service-name', SERVICE,
        '--frontend-origin', 'https://updated.example')
    assert api('/api/push/vapid-public-key')['publicKey'] == first_key
    assert api(path, device=device) == reserved
    run(sys.executable, str(DEPLOY), 'status', '--base-url', BASE, '--frontend-origin', 'https://updated.example')
    run('sudo', sys.executable, str(DEPLOY), 'rollback', '--service-name', SERVICE)
    run(sys.executable, str(DEPLOY), 'status', '--base-url', BASE, '--frontend-origin', 'https://frontend.example')
    assert api('/api/push/vapid-public-key')['publicKey'] == first_key
    assert api(path, device=device) == reserved
    print('PASS: real systemd install, VAPID generation, CORS, persistent reservation, restart, update and rollback.')
finally:
    subprocess.run(['sudo', 'systemctl', 'disable', '--now', SERVICE], check=False)
