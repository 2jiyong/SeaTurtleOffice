"""world/와 scenarios/의 마크다운을 시작할 때 한 번 읽어 둔다.

규격은 .claude/skills/namu-ingest/references/schema.md, scenarios/README.md를 따른다.
"""

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

log = logging.getLogger(__name__)

HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
BULLET = re.compile(r"^(?:[-*]|\d+\.)\s+(.*)$")


@dataclass(frozen=True)
class WorldDoc:
    """세계관 문서 하나(베이스 섹션 또는 상세 항목). text는 AI에게 보낼 본문이다."""

    id: str | None
    title: str | None
    text: str


@dataclass(frozen=True)
class Scenario:
    slug: str
    title: str
    uses: list[str]
    problem: str
    truth: str
    key_points: list[str]
    judge_notes: str
    hints: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class MarkdownDoc:
    """YAML 프런트매터와 본문으로 나눈 마크다운 문서."""

    meta: dict
    body: str

    @staticmethod
    def parse(raw: str) -> "MarkdownDoc":
        text = raw.replace("\r\n", "\n")
        if not text.startswith("---\n"):
            return MarkdownDoc({}, text)
        end = text.find("\n---\n", 4)
        if end < 0:
            raise ValueError("프런트매터가 닫히지 않았습니다")
        front = text[4:end]
        try:
            loaded = yaml.safe_load(front)
            meta = loaded if isinstance(loaded, dict) else {}
        except yaml.YAMLError:
            # 값에 따옴표 없는 ": "가 들어간 경우 등. 한 줄에 "키: 값" 하나라고 보고 읽는다.
            meta = _parse_loosely(front)
        return MarkdownDoc(meta, text[end + 5 :])

    def string(self, key: str) -> str | None:
        value = self.meta.get(key)
        return None if value is None else str(value)

    def list(self, key: str) -> list[str]:
        value = self.meta.get(key)
        if isinstance(value, list):
            return [str(v) for v in value]
        return [] if value is None else [str(value)]

    def clean_body(self) -> str:
        """AI에게 보낼 본문: HTML 주석을 지운다."""
        return HTML_COMMENT.sub("", self.body).strip()

    def sections(self) -> dict[str, str]:
        """'## 제목' 단위로 본문을 나눈다. 첫 '##' 앞의 내용은 버린다."""
        result: dict[str, str] = {}
        current: str | None = None
        buf: list[str] = []
        for line in self.clean_body().split("\n"):
            if line.startswith("## "):
                if current is not None:
                    result[current] = "\n".join(buf).strip()
                current = line[3:].strip()
                buf = []
            elif current is not None:
                buf.append(line)
        if current is not None:
            result[current] = "\n".join(buf).strip()
        return result


def _parse_loosely(front: str) -> dict:
    meta: dict = {}
    for line in front.split("\n"):
        colon = line.find(":")
        if colon <= 0 or line[0].isspace():
            continue
        key, value = line[:colon].strip(), line[colon + 1 :].strip()
        if value.startswith("[") and value.endswith("]"):
            inner = value[1:-1].strip()
            meta[key] = [v.strip() for v in inner.split(",")] if inner else []
        else:
            meta[key] = re.sub(r'^"(.*)"$', r"\1", value)
    return meta


def list_items(section: str | None) -> list[str]:
    """'- ' 또는 '1. '로 시작하는 목록 항목을 뽑는다. 들여쓴 이어지는 줄은 앞 항목에 붙인다."""
    items: list[str] = []
    if not section:
        return items
    for line in section.split("\n"):
        m = BULLET.match(line)
        if m:
            items.append(m.group(1).strip())
        elif items and line.strip() and line[0].isspace():
            items[-1] = items[-1] + " " + line.strip()
    return items


def _markdown_files(directory: Path, recursive: bool) -> list[Path]:
    paths = directory.rglob("*.md") if recursive else directory.glob("*.md")
    return sorted(p for p in paths if p.is_file() and not p.name.startswith("_") and p.name != "README.md")


class ContentRepository:
    def __init__(self, root: Path):
        root = root.resolve()
        if not (root / "world").is_dir() or not (root / "scenarios").is_dir():
            raise RuntimeError(f"world/와 scenarios/를 찾을 수 없습니다: {root} (GAME_CONTENT_DIR를 확인하세요)")
        self.root = root
        self.base = self._load_base(root / "world/base")
        self.details = self._load_details(root / "world/detail")
        self.scenarios = self._load_scenarios(root / "scenarios")
        log.info(
            "콘텐츠를 읽었습니다: %s (베이스 %d개, 상세 %d개, 시나리오 %d개)",
            root, len(self.base), len(self.details), len(self.scenarios),
        )
        for slug, ids in self.missing_uses().items():
            log.warning("시나리오 %s의 uses에 없는 항목이 있습니다: %s", slug, ids)

    def scenario(self, slug: str) -> Scenario | None:
        return self.scenarios.get(slug)

    def details_for(self, scenario: Scenario) -> list[WorldDoc]:
        """시나리오의 uses 중 상세 항목만 돌려준다. 베이스 ID(base.*)는 이미 항상 들어가므로 건너뛴다."""
        return [self.details[i] for i in scenario.uses if not i.startswith("base.") and i in self.details]

    def missing_uses(self) -> dict[str, list[str]]:
        """시나리오별로 uses에 적혀 있지만 world/에 없는 ID."""
        result = {}
        for s in self.scenarios.values():
            missing = [i for i in s.uses if not i.startswith("base.") and i not in self.details]
            if missing:
                result[s.slug] = missing
        return result

    @staticmethod
    def _load_base(directory: Path) -> list[WorldDoc]:
        docs = []
        for path in _markdown_files(directory, recursive=False):
            doc = MarkdownDoc.parse(path.read_text(encoding="utf-8"))
            docs.append(WorldDoc(doc.string("id"), None, doc.clean_body()))
        return docs

    @staticmethod
    def _load_details(directory: Path) -> dict[str, WorldDoc]:
        result = {}
        for path in _markdown_files(directory, recursive=True):
            doc = MarkdownDoc.parse(path.read_text(encoding="utf-8"))
            doc_id = doc.string("id")
            if doc_id is None:
                log.warning("id가 없는 상세 문서를 건너뜁니다: %s", path)
                continue
            # 출처 섹션은 판정에 쓰이지 않으므로 뺀다.
            text = "\n\n".join(f"## {h}\n{c}" for h, c in doc.sections().items() if h != "출처")
            result[doc_id] = WorldDoc(doc_id, doc.string("title"), text.strip())
        return result

    @staticmethod
    def _load_scenarios(directory: Path) -> dict[str, Scenario]:
        result = {}
        for path in _markdown_files(directory, recursive=False):
            doc = MarkdownDoc.parse(path.read_text(encoding="utf-8"))
            sec = doc.sections()
            if "문제" not in sec or "진상" not in sec:
                log.warning("문제/진상 섹션이 없는 시나리오를 건너뜁니다: %s", path)
                continue
            slug = path.stem
            result[slug] = Scenario(
                slug=slug,
                title=doc.string("title") or slug,
                uses=doc.list("uses"),
                problem=sec["문제"],
                truth=sec["진상"],
                key_points=list_items(sec.get("핵심 포인트")),
                judge_notes=sec.get("판정 메모", ""),
                hints=list_items(sec.get("힌트")),
            )
        return result
