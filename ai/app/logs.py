"""로그 설정. 요청마다 요청 ID를 모든 로그에 찍는다(백엔드가 보낸 X-Request-Id와 같은 값).

LOG_FORMAT=json이면 JSON 한 줄(배포), 아니면 사람이 읽는 한 줄(개발).
"""

import json
import logging
import re
import uuid
from contextvars import ContextVar

HEADER = "X-Request-Id"
_VALID = re.compile(r"^[A-Za-z0-9-]{8,64}$")

request_id: ContextVar[str] = ContextVar("request_id", default="-")

# LogRecord가 원래 갖는 속성. 이 밖의 속성(extra로 넘긴 것)만 JSON에 더한다.
_STANDARD = set(vars(logging.LogRecord("", 0, "", 0, "", None, None))) | {"message", "asctime", "request_id"}


def valid_request_id(value: str | None) -> str:
    """들어온 값이 규칙(영숫자와 -, 8~64자)에 맞으면 그대로, 아니면 새 UUID."""
    if value and _VALID.match(value):
        return value
    return str(uuid.uuid4())


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        data = {
            "time": self.formatTime(record),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "request_id": getattr(record, "request_id", "-"),
        }
        data.update({k: v for k, v in vars(record).items() if k not in _STANDARD})
        if record.exc_info:
            data["error"] = self.formatException(record.exc_info)
        return json.dumps(data, ensure_ascii=False, default=str)


_base_factory = logging.getLogRecordFactory()


def _record_with_request_id(*args, **kwargs) -> logging.LogRecord:
    record = _base_factory(*args, **kwargs)
    record.request_id = request_id.get()
    return record


def setup_logging(fmt: str) -> None:
    logging.setLogRecordFactory(_record_with_request_id)
    handler = logging.StreamHandler()
    if fmt.lower() == "json":
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s [%(request_id)s]: %(message)s"))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(logging.INFO)
