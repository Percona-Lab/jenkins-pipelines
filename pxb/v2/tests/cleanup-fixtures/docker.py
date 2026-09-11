#!/usr/bin/env python3
"""Docker boundary for the real run-test cleanup contract tests."""
import json
import os
from pathlib import Path
import sys


state_path = Path(os.environ['PXB_CLEANUP_FIXTURE'])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
result = 0
if args[:2] == ['run', '--rm']:
    state['main_runs'] += 1
    result = state['main_exit']
elif args[:2] == ['network', 'list']:
    print('fixture pxb_network bridge local')
elif args[:2] == ['run', '-d']:
    name = args[args.index('--name') + 1]
    if any(container['name'] == name for container in state['containers'].values()):
        result = 125
    else:
        identifier = {'s3': 'a', 'vault': 'd', 'kmip': 'e'}[name] * 64
        state['containers'][identifier] = {'name': name}
        print(identifier)
elif args[0] == 'inspect':
    print('    "IPAddress": "192.0.2.10",')
elif args[0] == 'cp':
    pass
elif args[0] == 'logs':
    print('export VAULT_TOKEN=fixture-token')
elif args[:2] == ['rm', '--force']:
    state['cleanup_targets'].extend(args[2:])
    if state.get('cleanup_exit'):
        result = state['cleanup_exit']
    else:
        for target in args[2:]:
            identifier = target if target in state['containers'] else next(
                (key for key, value in state['containers'].items() if value['name'] == target), None)
            if identifier is None:
                result = 1
            else:
                del state['containers'][identifier]
else:
    raise AssertionError(f'Unexpected Docker command: {args}')
state_path.write_text(json.dumps(state))
sys.exit(result)
