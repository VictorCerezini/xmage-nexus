"""Print the signed-in providers' models and reasoning levels for the model seat picker.

python -m llm.catalog [out.json] reads each CLI's sign-in and model list without starting a model turn
or a game. The catalog never contains credentials or account identity; a seat request names it.
"""

import json
import sys
from pathlib import Path

from llm import paths, providers


def main(argv):
    paths.RUN_ROOT.mkdir(parents=True, exist_ok=True)
    paths.PLAYER_CWD.mkdir(parents=True, exist_ok=True)
    try:
        catalog = providers.discover_all()
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    for entry in catalog["unavailable"]:
        print(f"{entry['id']} unavailable: {entry['reason']}", file=sys.stderr)
    text = json.dumps(catalog, indent=2) + "\n"
    if argv:
        Path(argv[0]).write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
