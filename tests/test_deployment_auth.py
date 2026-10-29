"""Exercise the actual workflow's sudo helper with local command doubles only."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/deployVps.yml'
BASH = os.environ.get('DEPLOY_TEST_BASH') or shutil.which('bash')


@unittest.skipUnless(BASH, 'bash is required for deployment shell tests')
class DeploymentAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='deployment-auth-')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.bin = self.directory / 'bin'
        self.bin.mkdir()
        self.calls = self.directory / 'sudo-calls'
        self.arguments = self.directory / 'sudo-args'
        self.password = "synthetic 'quoted' \"password\" with $ and \\ spaces"
        self.env = os.environ.copy()
        self.env.update({
            'PATH': str(self.bin) + os.pathsep + self.env.get('PATH', ''),
            'TEST_UID': '1000',
            'MOCK_BIN': self.bin.as_posix(),
            'VPS_SUDO_PASSWORD': self.password,
            'EXPECTED_PASSWORD': self.password,
            'CALLS_FILE': self.calls.as_posix(),
            'ARGS_FILE': self.arguments.as_posix(),
        })
        self.write('id', '#!/bin/sh\nprintf "%s\\n" "$TEST_UID"\n')
        self.write('sudo', '''#!/bin/sh
set -eu
printf 'called\\n' >> "$CALLS_FILE"
printf '%s\\n' "$@" > "$ARGS_FILE"
test "$1" = '-S'; shift
test "$1" = '-p'; shift
test "$1" = ''; shift
test "$1" = '--'; shift
if [ "${TEST_NOPASSWD-}" = 1 ]; then exec "$@"; fi
IFS= read -r supplied
if [ "$supplied" != "$EXPECTED_PASSWORD" ]; then
  echo 'authentication rejected' >&2
  exit 1
fi
exec "$@"
''')
        workflow = WORKFLOW.read_text(encoding='utf-8')
        helpers = re.findall(r'(?ms)^            as_root\(\) \{\n.*?^            \}', workflow)
        self.assertEqual(3, len(helpers))
        self.assertTrue(all(helper == helpers[0] for helper in helpers), 'All SSH steps must use the same authentication helper')
        self.helper = '\n'.join(line[12:] for line in helpers[0].splitlines())

    def write(self, name, body):
        path = self.bin / name
        path.write_text(body, encoding='utf-8', newline='\n')
        path.chmod(0o755)

    def run_helper(self, command):
        result = subprocess.run([BASH, '--noprofile', '--norc', '-eu', '-c', 'if command -v cygpath >/dev/null 2>&1; then fixture_bin=$(cygpath -u "$MOCK_BIN"); else fixture_bin=$MOCK_BIN; fi; export PATH="$fixture_bin:$PATH"\n' + self.helper + '\n' + command],
                                env=self.env, text=True, capture_output=True)
        self.assertNotIn(self.password, result.stdout + result.stderr)
        return result

    def test_password_uses_stdin_and_preserves_special_characters(self):
        result = self.run_helper("as_root printf '%s' connected")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('connected', result.stdout)
        self.assertNotIn(self.password, self.arguments.read_text())
        self.assertEqual(['called'], self.calls.read_text().splitlines())

    def test_root_does_not_require_sudo_or_a_password(self):
        self.env['TEST_UID'] = '0'
        self.env.pop('VPS_SUDO_PASSWORD')
        result = self.run_helper("as_root printf '%s' root-connected")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('root-connected', result.stdout)
        self.assertFalse(self.calls.exists())

    def test_passwordless_sudo_still_works(self):
        self.env['TEST_NOPASSWD'] = '1'
        self.env.pop('VPS_SUDO_PASSWORD')
        result = self.run_helper("as_root printf '%s' passwordless")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('passwordless', result.stdout)

    def test_rejected_password_fails_without_retrying_command(self):
        self.env['EXPECTED_PASSWORD'] = 'different-password'
        result = self.run_helper("as_root printf '%s' must-not-run")
        self.assertEqual(1, result.returncode)
        self.assertNotIn('must-not-run', result.stdout)
        self.assertEqual(['called'], self.calls.read_text().splitlines())

    def test_command_failure_is_preserved_without_a_retry(self):
        result = self.run_helper("as_root sh -c 'exit 7'")
        self.assertEqual(7, result.returncode)
        self.assertEqual(['called'], self.calls.read_text().splitlines())

    def test_build_has_no_secret_references_and_sudo_secret_is_optional(self):
        workflow = WORKFLOW.read_text(encoding='utf-8')
        build = workflow.split('  build:\n', 1)[1].split('  deploy:\n', 1)[0]
        self.assertNotIn('secrets.', build)
        self.assertEqual(3, workflow.count('VPS_SUDO_PASSWORD: ${{ secrets.VPS_SUDO_PASSWORD || secrets.VPS_PASSWORD }}'))
        self.assertNotIn('sudo -n ', workflow)


if __name__ == '__main__':
    unittest.main()
