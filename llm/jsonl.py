"""Read complete records while another process appends to a JSONL trace."""

import json


def read_complete_events(path):
    if not path.exists():
        return []
    # A writer may still be writing the final record. Complete malformed records fail loudly.
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines(keepends=True)
        if line.endswith("\n") and line.strip()
    ]
