"""Register the global command schema without connecting to Discord Gateway."""
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

API = 'https://discord.com/api/v10'


def command_definition():
    return {
        'name': 'gvd',
        'description': 'GvDのチャンネル応答設定',
        'type': 1,
        'contexts': [0],
        'integration_types': [0],
        'options': [
            {
                'type': 1,
                'name': name,
                'description': description,
                'options': [{'type': 7, 'name': 'ch', 'description': '対象チャンネル', 'required': True}],
            }
            for name, description in [
                ('skipch', 'このチャンネルではリアクションと返信を停止する'),
                ('readch', 'このチャンネルでリアクションと返信を再開する'),
            ]
        ],
    }


def register(token, opener=urlopen):
    if not token or any(character.isspace() for character in token):
        raise ValueError('A raw DISCORD_BOT_TOKEN is required.')
    headers = {'Authorization': 'Bot ' + token, 'User-Agent': 'ChatGPTviaDiscord command registration'}
    with opener(Request(API + '/users/@me', headers=headers), timeout=20) as response:
        account = json.load(response)
    application_id = account.get('id', '')
    if not account.get('bot') or not application_id.isdecimal():
        raise ValueError('The configured credential must identify a Discord Bot.')
    request = Request(API + '/applications/' + application_id + '/commands',
                      data=json.dumps(command_definition(), ensure_ascii=False).encode('utf-8'),
                      headers={**headers, 'Content-Type': 'application/json'}, method='POST')
    with opener(request, timeout=20) as response:
        command = json.load(response)
    if (command.get('name') != 'gvd' or command.get('guild_id') is not None
            or [option['name'] for option in command.get('options', [])] != ['skipch', 'readch']):
        raise ValueError('Discord did not confirm the expected global command definition.')
    return command['id']


def main():
    try:
        command_id = register(os.environ.get('DISCORD_BOT_TOKEN', '').strip())
        print('GLOBAL_COMMAND_REGISTERED name=gvd id=' + command_id + ' subcommands=skipch,readch')
        return 0
    except HTTPError as error:
        print('Command registration failed: Discord HTTP status ' + str(error.code) + '.', file=sys.stderr)
    except (URLError, OSError):
        print('Command registration failed: network connection error.', file=sys.stderr)
    except (ValueError, KeyError, TypeError):
        print('Command registration failed: token or response validation failed.', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
