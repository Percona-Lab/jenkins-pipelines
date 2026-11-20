"""Execute the real runner with a fake Docker CLI, without creating containers.

Run from the repository root:
    uv run --no-project python pxb/v2/tests/test_run_test_cleanup.py
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
RUN_TEST = ROOT / 'pxb/v2/docker/run-test'
DOCKER = Path(__file__).with_name('cleanup-fixtures') / 'docker.py'


class RunTestCleanup(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pxb-run-test-cleanup-')
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        runner_root = self.work / 'pxb/v2'
        for name in ('docker', 'local', 'sources'):
            (runner_root / name).mkdir(parents=True)
        self.runner = runner_root / 'docker/run-test'
        shutil.copy2(RUN_TEST, self.runner)
        self.commands = self.work / 'bin'
        self.commands.mkdir()
        shutil.copy2(DOCKER, self.commands / 'docker')
        (self.commands / 'docker').chmod(0o755)
        self.state_path = self.work / 'docker-state.json'
        self.state = {'containers': {}, 'main_exit': 0, 'main_runs': 0, 'cleanup_targets': []}
        self.environment = dict(os.environ,
                                PATH=str(self.commands) + os.pathsep + os.environ['PATH'],
                                PXB_CLEANUP_FIXTURE=str(self.state_path),
                                XTRABACKUP_TARGET='innodb80', INNODB80_VERSION='8.0.35',
                                CMAKE_BUILD_TYPE='RelWithDebInfo',
                                WITH_XBCLOUD_TESTS='false', WITH_VAULT_TESTS='false',
                                WITH_KMIP_TESTS='false', WITH_KEYCLOAK_TESTS='false',
                                WITH_AZURITE='false')

    def run_runner(self):
        self.state_path.write_text(json.dumps(self.state))
        result = subprocess.run(['bash', str(self.runner), 'oraclelinux:9', 'x86_64'],
                                env=self.environment, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=10, check=False)
        self.state = json.loads(self.state_path.read_text())
        return result

    def test_disabled_services_do_not_turn_success_into_failure(self):
        result = self.run_runner()
        self.assertEqual(self.state['main_runs'], 1)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_cleanup_failure_preserves_the_original_test_failure(self):
        self.environment['WITH_XBCLOUD_TESTS'] = 'true'
        self.state.update(main_exit=17, cleanup_exit=23)
        result = self.run_runner()
        self.assertEqual(self.state['main_runs'], 1)
        self.assertTrue(self.state['cleanup_targets'])
        self.assertEqual(result.returncode, 17, result.stderr)

    def test_cleanup_removes_only_the_container_created_by_this_run(self):
        self.environment['WITH_XBCLOUD_TESTS'] = 'true'
        existing = {'b' * 64: {'name': 'vault'}, 'c' * 64: {'name': 'kmip'}}
        self.state['containers'] = dict(existing)
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state['containers'], existing)
        self.assertEqual(self.state['cleanup_targets'], ['a' * 64])

    def test_cleanup_failure_does_not_replace_success(self):
        self.environment['WITH_XBCLOUD_TESTS'] = 'true'
        self.state['cleanup_exit'] = 23
        result = self.run_runner()
        self.assertEqual(self.state['main_runs'], 1)
        self.assertEqual(self.state['cleanup_targets'], ['a' * 64])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Warning: helper-container cleanup failed', result.stderr)

    def test_all_enabled_helpers_are_cleaned_by_their_created_ids(self):
        for name in ('WITH_XBCLOUD_TESTS', 'WITH_VAULT_TESTS', 'WITH_KMIP_TESTS'):
            self.environment[name] = 'true'
        existing = {'f' * 64: {'name': 'another-job'}}
        self.state['containers'] = dict(existing)
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state['main_runs'], 1)
        self.assertEqual(self.state['containers'], existing)
        self.assertEqual(self.state['cleanup_targets'], ['a' * 64, 'd' * 64, 'e' * 64])

    def test_startup_name_conflict_does_not_remove_the_existing_container(self):
        self.environment['WITH_XBCLOUD_TESTS'] = 'true'
        existing = {'f' * 64: {'name': 's3'}}
        self.state['containers'] = dict(existing)
        result = self.run_runner()
        self.assertEqual(result.returncode, 125, result.stderr)
        self.assertEqual(self.state['main_runs'], 0)
        self.assertEqual(self.state['containers'], existing)
        self.assertEqual(self.state['cleanup_targets'], [])

    def test_later_startup_failure_cleans_only_previously_created_helpers(self):
        self.environment.update(WITH_XBCLOUD_TESTS='true', WITH_VAULT_TESTS='true')
        existing = {'f' * 64: {'name': 'vault'}}
        self.state['containers'] = dict(existing)
        result = self.run_runner()
        self.assertEqual(result.returncode, 125, result.stderr)
        self.assertEqual(self.state['main_runs'], 0)
        self.assertEqual(self.state['containers'], existing)
        self.assertEqual(self.state['cleanup_targets'], ['a' * 64])

    def test_keycloak_starts_for_percona_server_and_is_passed_to_the_tests(self):
        self.environment.update(WITH_KEYCLOAK_TESTS='true', XTRABACKUP_TARGET='xtradb80',
                                XTRADB80_VERSION='8.4.11-11')
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state['cleanup_targets'], ['9' * 64])
        self.assertIn("export KEYCLOAK_URL='http://192.0.2.10:8080'", self.state['main_script'])

    def test_keycloak_imports_the_pxb_realm(self):
        self.environment.update(WITH_KEYCLOAK_TESTS='true', XTRABACKUP_TARGET='xtradb80',
                                XTRADB80_VERSION='8.4.11-11')
        shutil.copy2(ROOT / 'pxb/v2/docker/keycloak-realm-pxb.json',
                     self.runner.parent / 'keycloak-realm-pxb.json')
        self.run_runner()
        args = self.state['started']['keycloak']
        self.assertIn('--import-realm', args)
        mount = args[args.index('--mount') + 1]
        self.assertIn('destination=/opt/keycloak/data/import/', mount)
        source = mount.split('source=')[1].split(',')[0]
        realm = json.loads(Path(source).read_text())
        self.assertEqual(realm['realm'], 'pxb')
        self.assertEqual(realm['clients'][0]['clientId'], 'pxb-xtrabackup')
        self.assertTrue(realm['clients'][0]['directAccessGrantsEnabled'])

    def test_keycloak_that_is_not_running_fails_the_run(self):
        self.environment.update(WITH_KEYCLOAK_TESTS='true', XTRABACKUP_TARGET='xtradb80',
                                XTRADB80_VERSION='8.4.11-11')
        self.state['not_running'] = ['keycloak']
        result = self.run_runner()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(self.state['main_runs'], 0)
        self.assertEqual(self.state['cleanup_targets'], ['9' * 64])
        self.assertIn('Keycloak IP not found', result.stderr)

    def test_keycloak_memory_is_limited(self):
        self.environment.update(WITH_KEYCLOAK_TESTS='true', XTRABACKUP_TARGET='xtradb80',
                                XTRADB80_VERSION='8.4.11-11')
        self.run_runner()
        args = self.state['started']['keycloak']
        self.assertEqual(args[args.index('--memory') + 1], '1g')

    def test_no_keycloak_for_targets_without_the_server_plugin(self):
        self.environment.update(WITH_KEYCLOAK_TESTS='true')
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state['cleanup_targets'], [])
        self.assertIn("export KEYCLOAK_URL=''", self.state['main_script'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
