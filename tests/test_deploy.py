import base64
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deploy', Path(__file__).parents[1] / 'scripts/deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentSafetyTests(unittest.TestCase):
    def setUp(self):
        self.keys = {
            'VAPID_PUBLIC_KEY': base64.urlsafe_b64encode(b'\x04' + b'a' * 64).decode().rstrip('='),
            'VAPID_PRIVATE_KEY': base64.urlsafe_b64encode(b'b' * 32).decode().rstrip('='),
        }
        self.data = Path('/var/lib/nextstation-test')

    def config(self, existing=None, **kwargs):
        defaults = dict(keys=self.keys if existing is None else None,
                        frontend_origin='https://frontend.example', subject='mailto:ops@example.com',
                        port=None, data_dir=self.data)
        defaults.update(kwargs)
        return deploy.make_environment(existing or {}, **defaults)

    def test_update_preserves_keys_database_and_password(self):
        initial = self.config()
        updated = self.config(initial, frontend_origin='https://updated.example', subject=None)
        for key in (*deploy.KEY_NAMES, 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'VAPID_SUBJECT', 'SERVER_PORT'):
            self.assertEqual(initial[key], updated[key])

    def test_existing_pair_cannot_be_silently_rotated(self):
        initial = self.config()
        changed = dict(self.keys, VAPID_PRIVATE_KEY=base64.urlsafe_b64encode(b'c' * 32).decode().rstrip('='))
        with self.assertRaisesRegex(deploy.DeploymentError, 'Refusing to rotate'):
            self.config(initial, keys=changed)

    def test_missing_keys_fail_instead_of_enabling_fake_push(self):
        with self.assertRaises(deploy.DeploymentError):
            self.config(keys=None)

    def test_rejects_wildcard_path_credentials_and_insecure_frontend_origins(self):
        for origin in ('*', 'https://frontend.example/', 'https://frontend.example/path',
                       'http://frontend.example', 'https://user:password@frontend.example',
                       'https://frontend.example\nPUSH_ENABLED=false', ''):
            with self.subTest(origin=origin), self.assertRaises(deploy.DeploymentError):
                deploy.validate_origins(origin)

    def test_local_development_and_multiple_origins_are_supported(self):
        self.assertEqual(deploy.validate_origins('http://localhost:3000, https://frontend.example'),
                         'http://localhost:3000,https://frontend.example')

    def test_unmanaged_service_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            unit = Path(temporary) / 'existing.service'
            unit.write_text('[Service]\nExecStart=/usr/bin/existing\n', encoding='utf-8')
            with self.assertRaisesRegex(deploy.DeploymentError, 'unmanaged'):
                deploy.assert_managed(unit)
            self.assertIn('/usr/bin/existing', unit.read_text())

    def test_service_name_cannot_escape_target_directories(self):
        for service in ('../other', '/tmp/test', 'bad\nname', 'a.service'):
            with self.subTest(service=service), self.assertRaises(deploy.DeploymentError):
                deploy.host_paths(service)

    def test_secret_environment_round_trip_without_evaluating_shell(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'backend.env'
            original = self.config()
            deploy.write_atomic(path, deploy.env_text(original))
            self.assertEqual(original, deploy.read_env(path))

    def test_multiline_environment_injection_is_rejected(self):
        with self.assertRaises(deploy.DeploymentError):
            deploy.env_text({'VAPID_SUBJECT': 'mailto:ops@example.com\nSERVER_PORT=1'})

    def test_legacy_health_or_disabled_vapid_is_not_readiness(self):
        with patch.object(deploy, 'request_json', return_value='OK'):
            self.assertFalse(deploy.readiness('http://localhost:8081'))
        with patch.object(deploy, 'request_json', side_effect=[{'status': 'UP'}, {}]):
            self.assertFalse(deploy.readiness('http://localhost:8081'))

    def test_wrong_public_key_is_not_readiness(self):
        with patch.object(deploy, 'request_json', side_effect=[{'status': 'UP'}, {'publicKey': 'other'}]):
            self.assertFalse(deploy.readiness('http://localhost:8081', expected_key='expected'))

    def test_port_and_subject_validation(self):
        for port in (0, 80, 65536):
            with self.subTest(port=port), self.assertRaises(deploy.DeploymentError):
                self.config(port=port)
        with self.assertRaises(deploy.DeploymentError):
            self.config(subject='not-a-contact')


if __name__ == '__main__':
    unittest.main()
