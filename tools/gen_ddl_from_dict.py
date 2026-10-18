"""从 docs/contracts/database-schema.json 生成 Flyway DDL。

字典是字段唯一来源，DDL 不允许手工维护第二份。生成规则：
- 表默认 utf8mb4/utf8mb4_bin；CHAR(26) 一律 ascii/ascii_bin（ULID）；
- 父表在本迁移或更早迁移已建 => 外键内联；否则延后到 V7 用 ALTER TABLE 补，
  这样循环引用（Run↔Attempt、Job↔JobAttempt）不需要关闭 foreign_key_checks；
- 每条 CHECK 生成具名约束，便于集成测试断言具体失败原因。

冻结机制（为什么必须有）：
    迁移一旦在某个环境执行过，就已经是历史。若允许生成器继续按字典重写它，
    字典一改动就会把已执行迁移改写，本机与 CI 的 checksum 对不上，
    真实环境更是直接不可复现。因此：
    - docs/contracts/migrations.lock.json 记录已发布迁移的 sha256；
    - 冻结的迁移**不再被生成器写入**，字典变更只能落到新增迁移上；
    - `--check` 同时校验"冻结文件未被改动"和"未冻结文件与字典一致"。

用法：
    python tools/gen_ddl_from_dict.py            # 写文件（跳过已冻结的迁移）
    python tools/gen_ddl_from_dict.py --check    # 只比对，发现差异则退出码 1
    python tools/gen_ddl_from_dict.py --freeze   # 把当前迁移封存进 lock（CI 通过后执行）
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DICT_PATH = ROOT / 'docs/contracts/database-schema.json'
OUT_DIR = ROOT / 'repopilot-server/src/main/resources/db/migration'
LOCK_PATH = ROOT / 'docs/contracts/migrations.lock.json'

# 按字典顺序切分迁移；每组开头注释说明这组表为什么放在一起
GROUPS = [
    ('V2__identity_and_configuration.sql', '身份、数据集与评测方案配置', [
        'agents', 'agent_versions', 'datasets', 'dataset_versions', 'dataset_cases',
        'evaluator_definitions', 'evaluation_plans', 'evaluation_plan_versions',
        'evaluation_plan_items',
    ]),
    ('V3__experiment_and_execution.sql', '实验、执行记录、产物与轨迹', [
        'experiments', 'experiment_targets', 'evaluation_runs', 'run_attempts',
        'artifacts', 'trace_events',
    ]),
    ('V4__evaluation_batch_and_result.sql', '评测批次、批次 Run 登记、Job 与结果', [
        'evaluation_batches', 'evaluation_batch_runs', 'evaluation_jobs',
        'evaluation_job_attempts', 'evaluation_results',
    ]),
    ('V5__annotation_and_import.sql', '人工标注与导入记账', [
        'annotation_tasks', 'annotation_records', 'import_batches', 'import_records',
        'import_errors',
    ]),
    ('V6__reliability_helpers.sql', '可靠性辅助表（Outbox/Inbox/幂等），本任务只建表', [
        'outbox_events', 'inbox_events', 'api_idempotency_keys',
    ]),
]
DEFERRED_FILE = 'V7__deferred_foreign_keys.sql'
DEFERRED_TITLE = '延后外键：循环引用与父表后建的组合在此补齐'

NUMERIC_DEFAULT = re.compile(r'^-?\d+(\.\d+)?$')
FUNCTION_DEFAULT = re.compile(r'^[A-Z_]+\(.*\)$')
ID_TYPE = 'CHAR(26)'
ID_ATTRS = 'CHARACTER SET ascii COLLATE ascii_bin'


def q(name: str) -> str:
    return '`' + name + '`'


def esc(text: str) -> str:
    return text.replace('\\', '\\\\').replace("'", "''")


def column_sql(col: dict) -> str:
    name, ctype = col['name'], col['type']
    attrs = ' ' + ID_ATTRS if ctype == ID_TYPE else ''
    if col.get('generated_expression'):
        base = f'{q(name)} {ctype}{attrs} GENERATED ALWAYS AS ({col["generated_expression"]})'
        return base + (' STORED' if col.get('generated_stored') else ' VIRTUAL')
    base = f'{q(name)} {ctype}{attrs}'
    base += '' if col['nullable'] else ' NOT NULL'
    default = col.get('default')
    if default is not None:
        if ctype == 'BOOLEAN' and default in ('true', 'false'):
            # 字典写 JSON 语义的 true/false；MySQL 的 BOOLEAN 是 TINYINT(1)，
            # 直接给字符串 'true' 会被判为非法默认值
            base += ' DEFAULT ' + default.upper()
        elif NUMERIC_DEFAULT.match(default) or FUNCTION_DEFAULT.match(default):
            base += f' DEFAULT {default}'
        else:
            base += f" DEFAULT '{esc(default)}'"
    return base


def name_for(prefix: str, table: str, cols: list[str], width: int = 64) -> str:
    raw = f'{prefix}_{table}_' + '_'.join(cols)
    raw = re.sub(r'[^a-z0-9_]', '', raw.lower())
    if len(raw) <= width:
        return raw
    # 截断后加稳定后缀，避免两个长名字撞车；不用内置 hash()，它随进程加盐
    digest = hashlib.sha1(raw.encode('utf-8')).hexdigest()[:6]
    return raw[:width - 7].rstrip('_') + '_' + digest


def table_sql(name: str, t: dict, inline_fks: list[dict], deferred: list[dict]) -> str:
    lines: list[str] = []
    lines.append(f'CREATE TABLE {q(name)} (')
    body: list[str] = []
    for col in t['columns']:
        body.append('  ' + column_sql(col))
    body.append('  PRIMARY KEY (' + ', '.join(q(c) for c in t['primary_key']) + ')')
    for cols in t['unique_keys']:
        body.append(f'  UNIQUE KEY {q(name_for("uk", name, cols))} (' + ', '.join(q(c) for c in cols) + ')')
    for cols in t['indexes']:
        body.append(f'  KEY {q(name_for("idx", name, cols))} (' + ', '.join(q(c) for c in cols) + ')')
    for i, expr in enumerate(t['checks'], start=1):
        body.append(f'  CONSTRAINT {q(f"ck_{name}_{i}")} CHECK ({expr})')
    for fk in inline_fks:
        body.append(fk_clause(name, fk))
    lines.append(',\n'.join(body))
    lines.append(f") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='{esc(t['description'])}';")
    return '\n'.join(lines)


def fk_clause(table: str, fk: dict) -> str:
    cols = fk['columns']
    ref = fk['references_columns']
    return ('  CONSTRAINT ' + q(name_for('fk', table, cols))
            + ' FOREIGN KEY (' + ', '.join(q(c) for c in cols) + ')'
            + f' REFERENCES {q(fk["references_table"])} (' + ', '.join(q(c) for c in ref) + ')'
            + f' ON DELETE {fk["on_delete"]} ON UPDATE {fk["on_update"]}')


def validate(tables: dict) -> list[str]:
    problems: list[str] = []
    for name, t in tables.items():
        cols = {c['name']: c for c in t['columns']}
        for key in t['unique_keys'] + t['indexes']:
            for c in key:
                ctype = cols[c]['type']
                # 只有大对象与非标量类型不能直接进索引；字符列还要看键长
                if ctype.startswith(('TEXT', 'BLOB', 'JSON')):
                    problems.append(f'{name}: 索引列 {c} 类型 {ctype} 不能直接建索引')
                    continue
                m = re.match(r'^(?:VAR)?CHAR\((\d+)\)$', ctype)
                if m and int(m.group(1)) > 768:
                    problems.append(f'{name}: 索引列 {c} 长度 {ctype} 超过 utf8mb4 单列键长上限')
        for fk in t['foreign_keys']:
            parent = tables[fk['references_table']]
            pcols = {c['name']: c for c in parent['columns']}
            for c, pc in zip(fk['columns'], fk['references_columns']):
                if cols[c]['type'] != pcols[pc]['type']:
                    problems.append(f'{name}.{c} 与 {fk["references_table"]}.{pc} 类型不一致')
        for col in t['columns']:
            comment = col.get('description', '')
            if len(comment) > 1024:
                problems.append(f'{name}.{col["name"]} 注释超过 1024 字符')
    return problems


def build() -> dict[str, str]:
    data = json.loads(DICT_PATH.read_text(encoding='utf-8'))
    tables: dict[str, dict] = data['tables']
    problems = validate(tables)
    if problems:
        raise SystemExit('字典校验失败：\n  ' + '\n  '.join(problems))

    assigned = {name: fname for fname, _, names in GROUPS for name in names}
    missing = [n for n in tables if n not in assigned]
    if missing:
        raise SystemExit('字典中有表未分配迁移：' + ', '.join(missing))
    extra = [n for names in [g[2] for g in GROUPS] for n in names if n not in tables]
    if extra:
        raise SystemExit('迁移分配引用了字典中不存在的表：' + ', '.join(extra))
    if set(assigned) != set(tables):
        raise SystemExit('迁移分配与字典表集合不一致')

    files: dict[str, str] = {}
    emitted: set[str] = set()
    deferred: list[tuple[str, dict]] = []

    for file_name, title, names in GROUPS:
        chunks = [
            f'-- {file_name.split("__")[0]} {title}',
            '-- 由 tools/gen_ddl_from_dict.py 依据 docs/contracts/database-schema.json 生成，'
            '请改字典后重新生成，不要手改本文件。',
            '-- 外键策略：父表已建则内联；循环引用或父表后建的组合统一放到 V7。',
            '',
        ]
        for name in names:
            t = tables[name]
            inline = [fk for fk in t['foreign_keys'] if fk['references_table'] in emitted]
            late = [fk for fk in t['foreign_keys'] if fk['references_table'] not in emitted]
            chunks.append(f'-- {name}：{t["description"]}')
            chunks.append(table_sql(name, t, inline, deferred))
            chunks.append('')
            if late:
                deferred.extend((name, fk) for fk in late)
            emitted.add(name)
        files[file_name] = '\n'.join(chunks).rstrip() + '\n'

    chunks = [
        f'-- {DEFERRED_FILE.split("__")[0]} {DEFERRED_TITLE}',
        '-- 这些外键的父表在本迁移之前已全部存在，但子表先于父表创建会形成环，',
        '-- 因此在最后统一 ALTER TABLE 添加。全程不关闭 foreign_key_checks。',
        '',
    ]
    for table, fk in deferred:
        chunks.append(f'ALTER TABLE {q(table)} ADD ' + fk_clause(table, fk).strip() + ';')
    files[DEFERRED_FILE] = '\n'.join(chunks).rstrip() + '\n'

    return files


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def load_lock() -> dict[str, str]:
    """返回 {迁移文件名: sha256}；没有 lock 文件时视为全部未冻结。"""
    if not LOCK_PATH.exists():
        return {}
    data = json.loads(LOCK_PATH.read_text(encoding='utf-8'))
    return dict(data.get('frozen', {}))


def write_lock(files: dict[str, str]) -> None:
    payload = {
        'note': (
            '已发布迁移的 sha256 冻结记录。冻结后生成器不再覆写这些文件；'
            '表结构变更必须新增迁移，并同步字典，由 SchemaDictionaryIT 用真实库校验最终状态。'
        ),
        'generator': 'tools/gen_ddl_from_dict.py',
        'frozen': {name: sha256_text(content) for name, content in sorted(files.items())},
    }
    LOCK_PATH.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n'
    )


def main() -> int:
    files = build()
    frozen = load_lock()

    # 1) 冻结文件完整性：被改写就说明有人动了历史迁移
    tampered = [
        name for name, digest in frozen.items()
        if not (OUT_DIR / name).exists()
        or sha256_text((OUT_DIR / name).read_text(encoding='utf-8')) != digest
    ]

    # 2) 未冻结文件必须与字典一致（冻结文件代表历史，不与当前字典比对）
    stale = [
        name for name, content in files.items()
        if name not in frozen
        and (not (OUT_DIR / name).exists()
             or (OUT_DIR / name).read_text(encoding='utf-8') != content)
    ]

    if '--freeze' in sys.argv:
        if tampered or stale:
            print('存在不一致，拒绝冻结：' + ', '.join(tampered + stale), file=sys.stderr)
            return 1
        write_lock(files)
        print(f'OK：已冻结 {len(files)} 个迁移到 {LOCK_PATH.relative_to(ROOT)}')
        return 0

    if '--check' in sys.argv:
        if tampered:
            print('以下已冻结迁移被改动（历史迁移不可修改，请新增迁移）：'
                  + ', '.join(tampered), file=sys.stderr)
            return 1
        if stale:
            print('以下迁移文件与字典不一致：' + ', '.join(stale), file=sys.stderr)
            return 1
        print(f'OK：{len(files)} 个迁移文件与字典一致（其中 {len(frozen)} 个已冻结且未被改动）')
        return 0

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    skipped = []
    for name, content in files.items():
        if name in frozen:
            skipped.append(name)
            continue
        (OUT_DIR / name).write_text(content, encoding='utf-8', newline='\n')
        print(f'written {name}: {content.count(chr(10))} lines')
    if skipped:
        print(f'已冻结，跳过写入：{", ".join(sorted(skipped))}')
        drift = [n for n in skipped if sha256_text(files[n]) != frozen[n]]
        if drift:
            print(
                '注意：下列已冻结迁移的生成结果与冻结内容不一致，说明字典已变更。\n'
                '      不要改历史迁移，请新增迁移并在真实库上用 SchemaDictionaryIT 校验最终状态：'
                + ', '.join(drift),
                file=sys.stderr,
            )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
