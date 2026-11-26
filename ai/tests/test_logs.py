"""요청 ID와 로그 형식. 백엔드와 같은 요청 ID로 두 서버의 로그를 잇는다."""

import json
import logging
import uuid

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.logs import JsonFormatter, valid_request_id
from app.main import app


@pytest.fixture(scope="module")
def client():
    mp = pytest.MonkeyPatch()
    mp.setenv("LLM_PROVIDER", "fake")
    get_settings.cache_clear()
    with TestClient(app) as c:
        yield c
    get_settings.cache_clear()
    mp.undo()


def _is_uuid(value: str) -> bool:
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


def test_request_id_is_echoed(client):
    r = client.get("/health", headers={"X-Request-Id": "abcd-1234-efgh"})
    assert r.headers["X-Request-Id"] == "abcd-1234-efgh"


@pytest.mark.parametrize("bad", ["bad id", "x", "a" * 65, "abc\tdefgh", "abc_defgh!"])
def test_bad_request_id_is_replaced(client, bad):
    r = client.get("/health", headers={"X-Request-Id": bad})
    assert r.headers["X-Request-Id"] != bad
    assert _is_uuid(r.headers["X-Request-Id"])


def test_missing_request_id_is_generated(client):
    assert _is_uuid(client.get("/health").headers["X-Request-Id"])


def test_judgement_log_has_request_id(client, caplog):
    with caplog.at_level(logging.INFO, logger="judge"):
        client.post("/judge/question", json={"scenario": "boys-order", "question": "질문"},
                    headers={"X-Request-Id": "abcd-1234-efgh"})
    records = [r for r in caplog.records if r.name == "judge"]
    assert records, "판정 로그가 없다"
    assert records[-1].request_id == "abcd-1234-efgh"
    assert records[-1].ai_ms >= 0
    assert records[-1].model == "fake"


def test_json_formatter_one_line():
    record = logging.LogRecord("judge", logging.INFO, __file__, 1, "판정 %s\n끝", ("YES",), None)
    record.request_id = "abcd-1234-efgh"
    record.ai_ms = 12
    line = JsonFormatter().format(record)
    assert "\n" not in line
    data = json.loads(line)
    assert data["message"] == "판정 YES\n끝"
    assert data["request_id"] == "abcd-1234-efgh"
    assert data["ai_ms"] == 12
    assert data["level"] == "INFO"
    assert data["logger"] == "judge"


def test_valid_request_id_rules():
    assert valid_request_id("abcd-1234") == "abcd-1234"
    assert _is_uuid(valid_request_id(None))
    assert _is_uuid(valid_request_id("short"))
