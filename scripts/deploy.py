#!/usr/bin/env python3
"""Install/update the backend on a Linux systemd host, without third-party Python packages."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

MARKER = '# Managed by next-station-backend scripts/deploy.py'
KEY_NAMES = ('VAPID_PUBLIC_KEY', 'VAPID_PRIVATE_KEY')


class DeploymentError(Exception):
    pass


def run(*args: str, check=True, cwd=None):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if check and result.returncode:
        # Child output can contain environment details; do not echo it automatically.
        raise DeploymentError(f'Command failed: {args[0]} {args[1] if len(args) > 1 else ""}; check server logs.')
    return result


def read_env(path: Path) -> dict[str, str]:
    values = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            raise DeploymentError('Invalid environment file; use plain KEY=value lines (no shell commands).')
        # Values written by this installer are unquoted and contain no control characters.
        if any(ord(c) < 32 for c in value) or value.startswith(('"', "'")):
            raise DeploymentError('Environment values must be plain, unquoted, single-line values.')
        values[key] = value
    return values


def validate_key_pair_shape(values):
    try:
        decoded = [base64.b64decode(values[k] + '=' * (-len(values[k]) % 4), altchars=b'-_', validate=True)
                   for k in KEY_NAMES]
        if len(decoded[0]) != 65 or decoded[0][0] != 4 or len(decoded[1]) != 32:
            raise ValueError()
    except (KeyError, ValueError):
        raise DeploymentError('A valid Base64URL VAPID public/private key pair is required.') from None
    # The Java gateway validates the cryptographic pair at startup, before readiness succeeds.


def validate_origins(value: str) -> str:
    origins = []
    for origin in value.split(','):
        origin = origin.strip()
        parsed = urllib.parse.urlsplit(origin)
        try:
            port = parsed.port
        except ValueError:
            raise DeploymentError('Invalid frontend port.') from None
        local_http = parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1')
        if (not (parsed.scheme == 'https' or local_http) or not parsed.hostname
                or parsed.path or parsed.query or parsed.fragment or parsed.username or parsed.password
                or '*' in origin or any(c.isspace() for c in origin) or (port is not None and not 1 <= port <= 65535)):
            raise DeploymentError('Frontend origin must be an exact HTTPS origin without a trailing slash/path; localhost HTTP is allowed.')
        origins.append(origin)
    if not origins:
        raise DeploymentError('At least one frontend origin is required.')
    return ','.join(dict.fromkeys(origins))


def validate_subject(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    valid = (parsed.scheme == 'mailto' and '@' in parsed.path and not parsed.query and not parsed.fragment) or (
        parsed.scheme == 'https' and bool(parsed.hostname) and not parsed.username and not parsed.password)
    if not valid or any(c.isspace() for c in value):
        raise DeploymentError('VAPID subject must be a real mailto: contact or HTTPS contact URL.')
    return value


def make_environment(existing, keys, frontend_origin, subject, port, data_dir):
    result = dict(existing)
    if keys:
        validate_key_pair_shape(keys)
        if any(existing.get(k) and existing[k] != keys[k] for k in KEY_NAMES):
            raise DeploymentError('Refusing to rotate existing VAPID keys. Keep the existing pair and browser subscriptions.')
        result.update({k: keys[k] for k in KEY_NAMES})
    validate_key_pair_shape(result)
    result['CORS_ALLOWED_ORIGINS'] = validate_origins(frontend_origin or result.get('CORS_ALLOWED_ORIGINS', ''))
    result['VAPID_SUBJECT'] = validate_subject(subject or result.get('VAPID_SUBJECT', ''))
    selected_port = port if port is not None else int(result.get('SERVER_PORT', '8081'))
    if not 1024 <= selected_port <= 65535:
        raise DeploymentError('Use an unprivileged backend port between 1024 and 65535.')
    result.update(PUSH_ENABLED='true', ALARM_SCHEDULER_ENABLED='true',
                  SERVER_ADDRESS='127.0.0.1', SERVER_PORT=str(selected_port))
    result.setdefault('DB_URL', f'jdbc:h2:file:{data_dir}/nextstation;DB_CLOSE_ON_EXIT=FALSE')
    result.setdefault('DB_USERNAME', 'sa')
    result.setdefault('DB_PASSWORD', secrets.token_hex(24))
    return result


def write_atomic(path: Path, content: str, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.nextstation-', dir=path.parent)
    try:
        os.chmod(temporary, mode)
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as output:
            output.write(content)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def env_text(values):
    if any('\n' in value or '\r' in value for value in values.values()):
        raise DeploymentError('Multiline environment values are not allowed.')
    return '\n'.join(f'{key}={value}' for key, value in sorted(values.items())) + '\n'


def link_release(link: Path, target: Path):
    temporary = link.with_name(link.name + '.next')
    if temporary.exists() or temporary.is_symlink():
        temporary.unlink()
    temporary.symlink_to(target)
    os.replace(temporary, link)


def unit_text(service, java):
    return f'''{MARKER}
[Unit]
Description=Next Station backend
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=nextstation
Group=nextstation
WorkingDirectory=/var/lib/{service}
EnvironmentFile=/etc/{service}/backend.env
ExecStart={java} -jar /opt/{service}/current.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
TimeoutStopSec=30
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/{service}

[Install]
WantedBy=multi-user.target
'''


def generate_keys(jar, java):
    with tempfile.TemporaryDirectory(prefix='nextstation-vapid-') as temporary:
        run(java, '-Dloader.main=com.nextstation.backend.push.VapidKeyGenerator', '-cp', str(jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher', cwd=temporary)
        keys = read_env(Path(temporary) / '.env.vapid')
        validate_key_pair_shape(keys)
        return keys


def request_json(url):
    with urllib.request.urlopen(url, timeout=3) as response:
        return json.loads(response.read())


def readiness(base_url, expected_key=None, frontend_origin=None):
    try:
        if request_json(base_url + '/api/health') != {'status': 'UP'}:
            return False
        public = request_json(base_url + '/api/push/vapid-public-key').get('publicKey')
        if not public or (expected_key is not None and public != expected_key):
            return False
        if frontend_origin:
            request = urllib.request.Request(base_url + '/api/sessions/00000000-0000-4000-8000-000000000001/alarms',
                method='OPTIONS', headers={'Origin': frontend_origin, 'Access-Control-Request-Method': 'PUT',
                                           'Access-Control-Request-Headers': 'content-type,x-device-id'})
            with urllib.request.urlopen(request, timeout=3) as response:
                if response.headers.get('Access-Control-Allow-Origin') != frontend_origin:
                    return False
        return True
    except (OSError, ValueError, AttributeError):
        return False


def wait_ready(config):
    base_url = f'http://127.0.0.1:{config["SERVER_PORT"]}'
    for _ in range(45):
        if all(readiness(base_url, config['VAPID_PUBLIC_KEY'], origin)
               for origin in config['CORS_ALLOWED_ORIGINS'].split(',')):
            return True
        time.sleep(1)
    return False


def host_paths(service):
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,48}', service):
        raise DeploymentError('Invalid service name. Use lowercase letters, digits and hyphens.')
    return (Path('/opt') / service, Path('/etc') / service,
            Path('/var/lib') / service, Path('/etc/systemd/system') / (service + '.service'))


def require_admin():
    if not sys.platform.startswith('linux') or os.geteuid() != 0:
        raise DeploymentError('Run install/rollback on the Linux EC2 server with sudo. A health URL is not SSH access.')
    for executable in ('systemctl', 'java', 'useradd'):
        if not shutil.which(executable):
            raise DeploymentError(f'{executable} is required. Install Java 17 and use a systemd Linux host.')


def assert_managed(unit):
    if unit.exists() and not unit.read_text(encoding='utf-8').startswith(MARKER):
        raise DeploymentError('An unmanaged service already uses this name. Choose --service-name nextstation-push; existing services are never overwritten.')


def install(args):
    require_admin()
    app_dir, config_dir, data_dir, unit = host_paths(args.service_name)
    assert_managed(unit)
    jar = Path(args.jar).resolve()
    if not jar.is_file() or jar.suffix != '.jar':
        raise DeploymentError('--jar must point to the built executable JAR.')
    java = str(Path(shutil.which('java')).resolve())
    version = run(java, '-version').stderr
    match = re.search(r'version "(\d+)', version)
    if not match or int(match[1]) < 17:
        raise DeploymentError('Java 17 or newer is required.')
    env_file = config_dir / 'backend.env'
    existing = read_env(env_file)
    # Validate supplied configuration before generating keys or changing the server.
    validate_origins(args.frontend_origin or existing.get('CORS_ALLOWED_ORIGINS', ''))
    validate_subject(args.vapid_subject or existing.get('VAPID_SUBJECT', ''))
    keys = read_env(Path(args.vapid_env)) if args.vapid_env else None
    if args.vapid_env and not keys:
        raise DeploymentError('--vapid-env file is missing or empty.')
    if not existing and not keys:
        keys = generate_keys(jar, java)
    config = make_environment(existing, keys, args.frontend_origin, args.vapid_subject, args.port, data_dir)
    current = app_dir / 'current.jar'
    previous_target = current.resolve() if current.is_symlink() else None
    if current.exists() and not current.is_symlink():
        raise DeploymentError('Unmanaged current.jar exists; refusing to overwrite.')
    was_active = run('systemctl', 'is-active', '--quiet', args.service_name, check=False).returncode == 0
    was_enabled = run('systemctl', 'is-enabled', '--quiet', args.service_name, check=False).returncode == 0
    old_unit = unit.read_text(encoding='utf-8') if unit.exists() else None
    old_env = env_file.read_text(encoding='utf-8') if env_file.exists() else None
    changing_port = existing.get('SERVER_PORT') != config['SERVER_PORT']
    if not was_active or changing_port:
        with socket.socket() as listener:
            try:
                listener.bind(('127.0.0.1', int(config['SERVER_PORT'])))
            except OSError:
                raise DeploymentError('Backend port is already in use. Choose another --port; existing processes are never stopped.') from None
    import pwd
    try:
        account = pwd.getpwnam('nextstation')
    except KeyError:
        run('useradd', '--system', '--user-group', '--home-dir', '/nonexistent', '--shell', '/usr/sbin/nologin', 'nextstation')
        account = pwd.getpwnam('nextstation')
    config_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(config_dir, 0o700)
    data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chown(data_dir, account.pw_uid, account.pw_gid)
    os.chmod(data_dir, 0o700)
    releases = app_dir / 'releases'
    releases.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    release = releases / (digest + '.jar')
    if not release.exists():
        shutil.copyfile(jar, release)
        os.chmod(release, 0o644)
    if previous_target and old_env and old_unit:
        link_release(app_dir / 'previous.jar', previous_target)
        write_atomic(config_dir / 'backend.env.previous', old_env)
        write_atomic(config_dir / 'service.previous', old_unit)
    try:
        write_atomic(env_file, env_text(config))
        link_release(current, release)
        write_atomic(unit, unit_text(args.service_name, java), 0o644)
        run('systemctl', 'daemon-reload')
        run('systemctl', 'enable', args.service_name)
        run('systemctl', 'restart', args.service_name)
        if not wait_ready(config):
            raise DeploymentError('Readiness failed (health/public key/CORS). Inspect journalctl for the service.')
    except Exception:
        run('systemctl', 'stop', args.service_name, check=False)
        if previous_target and old_env and old_unit:
            link_release(current, previous_target)
            write_atomic(env_file, old_env)
            write_atomic(unit, old_unit, 0o644)
            run('systemctl', 'daemon-reload', check=False)
            if was_active:
                run('systemctl', 'start', args.service_name, check=False)
        if not was_enabled:
            run('systemctl', 'disable', args.service_name, check=False)
        raise
    print(f'Installed {args.service_name}; bound to 127.0.0.1:{config["SERVER_PORT"]}; starts at boot.')
    print(f'Environment (private): {env_file}; persistent data: {data_dir}')
    print('VAPID_PUBLIC_KEY=' + config['VAPID_PUBLIC_KEY'])
    print('Next: connect the existing HTTPS Nginx /api/ route to this port, then run the status command.')


def rollback(args):
    require_admin()
    app_dir, config_dir, _, unit = host_paths(args.service_name)
    assert_managed(unit)
    previous = app_dir / 'previous.jar'
    old_env = config_dir / 'backend.env.previous'
    old_unit = config_dir / 'service.previous'
    if not previous.is_symlink() or not previous.resolve().is_file() or not old_env.exists() or not old_unit.exists():
        raise DeploymentError('No previous managed release/configuration is available.')
    target = previous.resolve()
    if not target.is_relative_to((app_dir / 'releases').resolve()):
        raise DeploymentError('Previous release points outside this service release directory.')
    current = app_dir / 'current.jar'
    current_target = current.resolve()
    current_env = (config_dir / 'backend.env').read_text(encoding='utf-8')
    current_unit = unit.read_text(encoding='utf-8')
    target_env = old_env.read_text(encoding='utf-8')
    target_unit = old_unit.read_text(encoding='utf-8')
    run('systemctl', 'stop', args.service_name)
    try:
        link_release(current, target)
        write_atomic(config_dir / 'backend.env', target_env)
        write_atomic(unit, target_unit, 0o644)
        run('systemctl', 'daemon-reload')
        run('systemctl', 'start', args.service_name)
        if not wait_ready(read_env(config_dir / 'backend.env')):
            raise DeploymentError('Previous release did not become ready.')
    except Exception:
        run('systemctl', 'stop', args.service_name, check=False)
        link_release(current, current_target)
        write_atomic(config_dir / 'backend.env', current_env)
        write_atomic(unit, current_unit, 0o644)
        run('systemctl', 'daemon-reload', check=False)
        run('systemctl', 'start', args.service_name, check=False)
        raise
    link_release(previous, current_target)
    write_atomic(old_env, current_env)
    write_atomic(old_unit, current_unit)
    print('Rollback complete. VAPID/environment restored; the persistent database was not changed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    install_parser = sub.add_parser('install', help='Install or update a managed systemd service')
    install_parser.add_argument('--jar', required=True)
    install_parser.add_argument('--service-name', default='nextstation-backend')
    install_parser.add_argument('--frontend-origin', help='Comma-separated exact frontend HTTPS origins')
    install_parser.add_argument('--vapid-subject', help='Real mailto: contact or HTTPS contact URL')
    install_parser.add_argument('--vapid-env', help='Optional existing VAPID key file; never copied to GitHub')
    install_parser.add_argument('--port', type=int, help='Default 8081; preserved on updates')
    rollback_parser = sub.add_parser('rollback')
    rollback_parser.add_argument('--service-name', default='nextstation-backend')
    status_parser = sub.add_parser('status', help='Read-only HTTP health, VAPID and optional CORS check')
    status_parser.add_argument('--base-url', default='http://127.0.0.1:8081')
    status_parser.add_argument('--frontend-origin')
    args = parser.parse_args()
    try:
        if args.command == 'install':
            install(args)
        elif args.command == 'rollback':
            rollback(args)
        else:
            if args.frontend_origin:
                if ',' in args.frontend_origin:
                    raise DeploymentError('The status command checks one frontend origin at a time.')
                validate_origins(args.frontend_origin)
            if not readiness(args.base_url.rstrip('/'), frontend_origin=args.frontend_origin):
                raise DeploymentError('Not ready: expected JSON health UP, a public VAPID key, and permitted CORS origin.')
            print('READY: health, VAPID public key and requested CORS checks passed. Device receipt still requires a real browser test.')
    except (DeploymentError, OSError, ValueError) as error:
        # Do not print unexpected exception payloads that could include secrets.
        print(str(error) if isinstance(error, DeploymentError) else 'Deployment failed; inspect server state and service logs.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
