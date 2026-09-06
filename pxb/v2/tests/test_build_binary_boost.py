"""Check the actual builder's Boost defaults at the CMake process boundary."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
BUILDER = ROOT / 'pxb/v2/local/build-binary'
CMAKE_STOP = 73


class BuildBinaryBoost(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='pxb-build-boost-')
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name)
        self.source = self.work / 'source'
        self.source.mkdir()
        (self.source / 'XB_VERSION').write_text(
            'XB_VERSION_MAJOR=2\nXB_VERSION_MINOR=4\n'
            'XB_VERSION_PATCH=29\nXB_VERSION_EXTRA=\n')
        self.build = self.work / 'build'
        self.cache = self.build / 'source_downloads'
        self.commands = self.work / 'bin'
        self.commands.mkdir()
        self.record = self.work / 'commands.jsonl'
        for command in ('mkdir', 'nproc', 'uname', 'which', 'grep', 'sed'):
            executable = shutil.which(command)
            self.assertIsNotNone(executable, f'Required command: {command}')
            (self.commands / command).symlink_to(executable)
        recorder = (
            f'#!{sys.executable}\n'
            'import json, os, sys\n'
            'from pathlib import Path\n'
            'command = Path(sys.argv[0]).name\n'
            'with open(os.environ["PXB_BOOST_RECORD"], "a") as output:\n'
            '    output.write(json.dumps({"command": command, '
            '"args": sys.argv[1:], "cwd": os.getcwd()}) + "\\n")\n'
            f'sys.exit({CMAKE_STOP} if command in ("cmake", "cmake3") else 97)\n'
        )
        for command in ('cmake', 'cmake3', 'make', 'tar', 'wget', 'curl'):
            executable = self.commands / command
            executable.write_text(recorder)
            executable.chmod(0o755)
        self.environment = {
            'PATH': str(self.commands),
            'LC_ALL': 'C',
            'PXB_BOOST_RECORD': str(self.record),
            'DOCKER_OS': 'centos-7',
            'CMAKE_BUILD_TYPE': 'RelWithDebInfo',
            'CMAKE_OPTS': '',
            'MAKE_OPTS': '-j1',
        }

    def configure_arguments(self, expected_command='cmake3'):
        self.record.write_text('')
        result = subprocess.run(
            [shutil.which('bash'), str(BUILDER), str(self.build), str(self.source)],
            cwd=self.work, env=self.environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, check=False)
        self.assertEqual(result.returncode, CMAKE_STOP, result.stderr)
        calls = [json.loads(line) for line in self.record.read_text().splitlines()]
        self.assertEqual([call['command'] for call in calls], [expected_command])
        self.assertEqual(calls[0]['cwd'], str(self.build))
        return calls[0]['args']

    def test_24_release_uses_cached_boost_without_downloading_by_default(self):
        arguments = self.configure_arguments()
        self.assertIn('-DDOWNLOAD_BOOST=OFF', arguments)
        self.assertIn(f'-DWITH_BOOST={self.cache}', arguments)

    def test_24_debug_keeps_debug_mode_with_the_same_cached_boost_default(self):
        self.environment['CMAKE_BUILD_TYPE'] = 'Debug'
        arguments = self.configure_arguments()
        self.assertIn('-DWITH_DEBUG=ON', arguments)
        self.assertNotIn('-DBUILD_CONFIG=xtrabackup_release', arguments)
        self.assertIn('-DDOWNLOAD_BOOST=OFF', arguments)
        self.assertIn(f'-DWITH_BOOST={self.cache}', arguments)

    def test_80_preserves_its_existing_boost_defaults(self):
        (self.source / 'XB_VERSION').write_text(
            'XB_VERSION_MAJOR=8\nXB_VERSION_MINOR=0\n'
            'XB_VERSION_PATCH=35\nXB_VERSION_EXTRA=-36\n')
        (self.source / 'cmake').mkdir()
        (self.source / 'cmake/boost.cmake').write_text(
            'SET(BOOST_PACKAGE_NAME "boost_1_77_0")\n')
        self.cache.mkdir(parents=True)
        (self.cache / 'boost_1_77_0.tar.bz2').touch()
        self.environment['DOCKER_OS'] = 'oraclelinux-9'
        arguments = self.configure_arguments(expected_command='cmake')
        self.assertIn('-DDOWNLOAD_BOOST=ON', arguments)
        self.assertIn(f'-DWITH_BOOST={self.cache}', arguments)
        self.assertNotIn('-DDOWNLOAD_BOOST=OFF', arguments)

    def test_other_versions_do_not_gain_boost_defaults(self):
        self.environment['DOCKER_OS'] = 'oraclelinux-9'
        for major, minor in ((2, 3), (2, 5), (8, 4), (9, 6)):
            with self.subTest(version=f'{major}.{minor}'):
                (self.source / 'XB_VERSION').write_text(
                    f'XB_VERSION_MAJOR={major}\nXB_VERSION_MINOR={minor}\n'
                    'XB_VERSION_PATCH=0\nXB_VERSION_EXTRA=\n')
                arguments = self.configure_arguments(expected_command='cmake')
                self.assertEqual(
                    [argument for argument in arguments
                     if argument.startswith(('-DDOWNLOAD_BOOST=', '-DWITH_BOOST='))],
                    [])

    def test_24_keeps_explicit_caller_overrides_after_the_defaults(self):
        self.environment['CMAKE_OPTS'] = (
            '-DWITH_BOOST=/caller/boost -DDOWNLOAD_BOOST=ON -DWITH_SSL=system')
        arguments = self.configure_arguments()
        self.assertEqual(
            [argument for argument in arguments
             if argument.startswith(('-DDOWNLOAD_BOOST=', '-DWITH_BOOST='))],
            ['-DDOWNLOAD_BOOST=OFF', f'-DWITH_BOOST={self.cache}',
             '-DWITH_BOOST=/caller/boost', '-DDOWNLOAD_BOOST=ON'])
        self.assertEqual(arguments[-4:], [
            '-DWITH_BOOST=/caller/boost', '-DDOWNLOAD_BOOST=ON',
            '-DWITH_SSL=system', str(self.source)])


if __name__ == '__main__':
    unittest.main(verbosity=2)
