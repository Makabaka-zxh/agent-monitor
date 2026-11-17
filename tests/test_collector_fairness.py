"""Published task fairness must not enlarge the source scan or total list."""
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from agent_monitor import collector

AT = datetime(2026, 11, 1, 0, 0, tzinfo=timezone.utc)


def fixture_task(tool, i, offset, *, status='completed'):
    return collector._task(tool, f'example-{i}', f'Synthetic {tool} {i}', status,
                           AT + timedelta(seconds=i + offset), 'Example')


def run_snapshot(monkeypatch, tmp_path, *, codex_count=50, claude_count=50, limit=50):
    assert codex_count <= 50 and claude_count <= 50
    candidates = tuple(Path(str(i)) for i in range(codex_count))
    def recent(base, take, *, claude=False, codex_growth=None):
        assert codex_growth is None
        assert take == min(50, limit)
        return ([], 0, False) if claude else (candidates[:take], len(candidates), False)
    def parse(path, *, titles, include_output, now, diagnostics):
        assert include_output is False
        return fixture_task('codex', int(path.name), 0)
    monkeypatch.setattr(collector, '_recent_files', recent)
    monkeypatch.setattr(collector, '_codex_titles', lambda _: {})
    monkeypatch.setattr(collector, 'parse_codex_session', parse)
    monkeypatch.setattr(collector, '_hook_tasks', lambda take, now: [
        fixture_task('claude', i, 100, status='idle') for i in range(min(claude_count, take))])
    return collector.collect_snapshot(tmp_path/'codex', tmp_path/'claude', limit=limit)['tasks']


@pytest.mark.parametrize(('codex_count', 'claude_count', 'limit', 'expected'), [
    (50, 50, 50, {'codex': 20, 'claude': 30}),
    (8, 50, 50, {'codex': 8, 'claude': 42}),
    (50, 6, 50, {'codex': 44, 'claude': 6}),
    (50, 0, 50, {'codex': 50}),
    (50, 50, 4, {'codex': 2, 'claude': 2}),
    (50, 50, 1, {'claude': 1}),
    (50, 50, 0, {}),
])
def test_publish_keeps_provider_share_within_existing_caps(monkeypatch, tmp_path, codex_count,
                                                         claude_count, limit, expected):
    tasks = run_snapshot(monkeypatch, tmp_path, codex_count=codex_count, claude_count=claude_count,
                         limit=limit)
    assert Counter(t['tool'] for t in tasks) == expected
    assert len({t['id'] for t in tasks}) == len(tasks) <= limit
    stamps = [t['updated_at'] for t in tasks]
    assert stamps == sorted(stamps, reverse=True)
    assert all(t['output'] == '' for t in tasks)
    codex_stamps = [t['updated_at'] for t in tasks if t['tool']=='codex']
    assert codex_stamps == [
        fixture_task('codex', i, 0)['updated_at']
        for i in range(min(codex_count,limit)-1, min(codex_count,limit)-1-len(codex_stamps), -1)]


@pytest.mark.parametrize(('active_count', 'expected'), [
    (31, {'codex': 19, 'claude': 31}),
    (49, {'codex': 1, 'claude': 49}),
    (50, {'claude': 50}),
])
def test_reserve_never_evicts_original_live_rows(active_count, expected):
    codex = [fixture_task('codex', i, 0) for i in range(50)]
    claude = [
        fixture_task('claude', i, 100, status=('running' if i % 2 else 'waiting')
                     if i < active_count else 'idle')
        for i in range(50)
    ]
    pool = {t['id']: t for t in codex + claude}
    published = collector._published_tasks(pool, 50)
    assert Counter(t['tool'] for t in published) == expected
    assert all(t in published for t in claude[:active_count])
    assert len({t['id'] for t in published}) == len(published) == 50
    assert [t['updated_at'] for t in published] == sorted((t['updated_at'] for t in published), reverse=True)
