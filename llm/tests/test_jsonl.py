"""A partially appended trace must not crash the game supervisor."""

import json

import pytest

from llm.jsonl import read_complete_events


def test_partial_last_record_waits_for_the_writer(tmp_path):
    path = tmp_path / "events.jsonl"
    path.write_text('{"type":"ready"}\n{"type":')
    assert read_complete_events(path) == [{"type": "ready"}]
    with path.open("a") as file:
        file.write('"done"}\n')
    assert read_complete_events(path) == [{"type": "ready"}, {"type": "done"}]


def test_complete_malformed_record_is_an_error(tmp_path):
    path = tmp_path / "events.jsonl"
    path.write_text("{not valid}\n")
    with pytest.raises(json.JSONDecodeError):
        read_complete_events(path)


def test_missing_trace_is_not_yet_started(tmp_path):
    assert read_complete_events(tmp_path / "not-created.jsonl") == []
