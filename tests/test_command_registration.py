import importlib.util
import io
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('register_gvd_commands', ROOT / 'ops/register_gvd_commands.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CommandRegistrationTest(unittest.TestCase):
    def test_registers_only_the_global_gvd_definition(self):
        requests = []

        def opener(request, timeout):
            requests.append(request)
            self.assertEqual(20, timeout)
            if len(requests) == 1:
                return io.StringIO(json.dumps({'id': '123456789012345678', 'bot': True}))
            result = module.command_definition()
            result['id'] = '234567890123456789'
            return io.StringIO(json.dumps(result))

        self.assertEqual('234567890123456789', module.register('synthetic-token', opener))
        self.assertEqual(2, len(requests))
        self.assertEqual('GET', requests[0].get_method())
        self.assertEqual('POST', requests[1].get_method())
        self.assertEqual('https://discord.com/api/v10/applications/123456789012345678/commands', requests[1].full_url)
        payload = json.loads(requests[1].data)
        self.assertEqual([0], payload['contexts'])
        self.assertEqual([0], payload['integration_types'])
        self.assertEqual(['skipch', 'readch', 'mode', 'accept', 'reject', 'auth', 'model', 'limit'], [item['name'] for item in payload['options']])
        limit = next(item for item in payload['options'] if item['name'] == 'limit')
        self.assertEqual(1, limit['type'])
        self.assertFalse(limit.get('options'))
        for subcommand in payload['options'][:2]:
            self.assertEqual(1, subcommand['type'])
            self.assertEqual('ch', subcommand['options'][0]['name'])
            self.assertEqual(7, subcommand['options'][0]['type'])
            self.assertTrue(subcommand['options'][0]['required'])
        mode = payload['options'][2]['options'][0]
        self.assertEqual('mode', mode['name'])
        self.assertEqual(3, mode['type'])
        self.assertTrue(mode['required'])
        self.assertEqual([{'name': name, 'value': name} for name in ['thread', 'qa']], mode['choices'])
        title = payload['options'][2]['options'][1]
        self.assertEqual('title', title['name'])
        self.assertEqual(3, title['type'])
        self.assertFalse(title['required'])
        self.assertEqual(100, title['max_length'])

    def test_accept_is_optional_and_reject_requires_a_role(self):
        by_name = {item['name']: item for item in module.command_definition()['options']}
        self.assertEqual(8, by_name['accept']['options'][0]['type'])
        self.assertFalse(by_name['accept']['options'][0]['required'])
        self.assertEqual(8, by_name['reject']['options'][0]['type'])
        self.assertTrue(by_name['reject']['options'][0]['required'])
        self.assertEqual(['login', 'status', 'use', 'logout'],
                         [choice['value'] for choice in by_name['auth']['options'][0]['choices']])

    def test_missing_mode_title_is_not_reported_as_success(self):
        actual = module.command_definition()['options']
        actual[2]['options'].pop()
        self.assertFalse(module.matches_definition(actual, module.command_definition()['options']))

    def test_incomplete_registration_is_not_reported_as_success(self):
        def opener(request, timeout):
            if request.get_method() == 'GET':
                return io.StringIO(json.dumps({'id': '123456789012345678', 'bot': True}))
            result = module.command_definition()
            result['id'] = '234567890123456789'
            result['options'][2]['options'][0]['choices'] = []
            return io.StringIO(json.dumps(result))
        with self.assertRaises(ValueError):
            module.register('synthetic-token', opener)

    def test_discord_metadata_does_not_invalidate_the_expected_schema(self):
        actual = module.command_definition()['options']
        actual[2]['options'][0]['name_localizations'] = None
        self.assertTrue(module.matches_definition(actual, module.command_definition()['options']))

    def test_omitted_optional_required_flags_are_valid_defaults(self):
        actual = module.command_definition()['options']
        for subcommand in actual:
            for option in subcommand.get('options', []):
                if option.get('required') is False:
                    del option['required']
        self.assertTrue(module.matches_definition(actual, module.command_definition()['options']))

    def test_required_options_cannot_be_missing_or_changed_to_optional(self):
        actual = module.command_definition()['options']
        del actual[0]['options'][0]['required']
        self.assertEqual('options[0].options[0].required', module.mismatch_path(actual, module.command_definition()['options']))
        actual[0]['options'][0]['required'] = False
        self.assertFalse(module.matches_definition(actual, module.command_definition()['options']))

    def test_missing_auth_command_is_still_a_validation_failure(self):
        actual = [item for item in module.command_definition()['options'] if item['name'] != 'auth']
        self.assertFalse(module.matches_definition(actual, module.command_definition()['options']))

    def test_missing_limit_is_not_reported_as_success(self):
        def opener(request, timeout):
            if request.get_method() == 'GET':
                return io.StringIO(json.dumps({'id': '123456789012345678', 'bot': True}))
            result = module.command_definition()
            result['id'] = '234567890123456789'
            result['options'] = [option for option in result['options'] if option['name'] != 'limit']
            return io.StringIO(json.dumps(result))
        with self.assertRaises(ValueError):
            module.register('synthetic-token', opener)

    def test_missing_token_does_not_make_a_network_request(self):
        def forbidden(*args, **kwargs):
            self.fail('Invalid token must be rejected before sending requests')
        for token in ['', 'Bot token', 'token\nvalue']:
            with self.assertRaises(ValueError):
                module.register(token, forbidden)

    def test_human_credentials_are_not_used_to_register_commands(self):
        calls = []

        def opener(request, timeout):
            calls.append(request)
            return io.StringIO(json.dumps({'id': '123456789012345678', 'bot': False}))

        with self.assertRaises(ValueError):
            module.register('synthetic-token', opener)
        self.assertEqual(1, len(calls))


if __name__ == '__main__':
    unittest.main()
