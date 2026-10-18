"""从 database-schema.json 生成持久化实体、基础 Mapper 与枚举约束。

为什么不手写 28 个实体：字段、类型、可空性、枚举取值都在字典里，手抄一遍必然漂移，
而且漂移后只能靠人眼比对。生成器让"字典是唯一来源"成为可执行的事实，
CI 用 --check 就能发现有人改了字典却没重新生成。

用法：
    python tools/gen_java_from_dict.py            # 生成
    python tools/gen_java_from_dict.py --check    # 只比对
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DICT = ROOT / 'docs/contracts/database-schema.json'
SRC = ROOT / 'repopilot-server/src/main/java'

MODULES = {
    'agents': 'agent', 'agent_versions': 'agent',
    'datasets': 'dataset', 'dataset_versions': 'dataset', 'dataset_cases': 'dataset',
    'evaluator_definitions': 'evaluationconfig', 'evaluation_plans': 'evaluationconfig',
    'evaluation_plan_versions': 'evaluationconfig', 'evaluation_plan_items': 'evaluationconfig',
    'experiments': 'experiment', 'experiment_targets': 'experiment',
    'evaluation_runs': 'trace', 'run_attempts': 'trace', 'artifacts': 'trace',
    'trace_events': 'trace',
    'evaluation_batches': 'evaluation', 'evaluation_batch_runs': 'evaluation',
    'evaluation_jobs': 'evaluation', 'evaluation_job_attempts': 'evaluation',
    'evaluation_results': 'evaluation',
    'annotation_tasks': 'annotation', 'annotation_records': 'annotation',
    'import_batches': 'importing', 'import_records': 'importing', 'import_errors': 'importing',
    'outbox_events': 'reliability', 'inbox_events': 'reliability',
    'api_idempotency_keys': 'reliability',
}

# 枚举名 -> 使用它的 (表, 列)。同一取值集合只定义一次，避免出现 4 个内容相同的 VersionStatus。
ENUMS: dict[str, list[tuple[str, str]]] = {
    'LifecycleStatus': [('agents', 'status'), ('datasets', 'status')],
    'VersionStatus': [('agent_versions', 'status'), ('dataset_versions', 'status'),
                      ('evaluator_definitions', 'status'), ('evaluation_plan_versions', 'status')],
    'RunnerType': [('agent_versions', 'runner_type')],
    'EvaluatorType': [('evaluator_definitions', 'evaluator_type')],
    'RunMode': [('experiments', 'mode'), ('evaluation_runs', 'mode')],
    'ExperimentStatus': [('experiments', 'status')],
    'RunStatus': [('evaluation_runs', 'status')],
    'TaskOutcome': [('evaluation_runs', 'task_outcome')],
    'AttemptStatus': [('run_attempts', 'status'), ('evaluation_job_attempts', 'status')],
    'ArtifactStatus': [('artifacts', 'status')],
    'BatchTriggerType': [('evaluation_batches', 'trigger_type')],
    'BatchStatus': [('evaluation_batches', 'status'), ('import_batches', 'status')],
    'JobStatus': [('evaluation_jobs', 'status')],
    'Verdict': [('evaluation_results', 'verdict'), ('annotation_records', 'verdict')],
    'GeneratedBy': [('evaluation_results', 'generated_by')],
    'AnnotationTaskStatus': [('annotation_tasks', 'status')],
    'AnnotationAction': [('annotation_records', 'action')],
    'ImportType': [('import_batches', 'import_type')],
    'ImportRecordStatus': [('import_records', 'status')],
    'OutboxStatus': [('outbox_events', 'status')],
}

ULID_HANDLER = 'com.repopilot.common.id.UlidTypeHandler'


def pascal(text: str) -> str:
    return ''.join(part.capitalize() for part in text.split('_'))


def singular(text: str) -> str:
    """表名 -> 实体名：agents -> Agent，api_idempotency_keys -> ApiIdempotencyKey。"""
    head, *rest = text.split('_')
    last = rest[-1] if rest else head
    if last.endswith('ies'):
        last = last[:-3] + 'y'
    elif last.endswith(('ches', 'shes', 'sses', 'xes')):
        # batches -> batch、cases 走普通复数规则
        last = last[:-2]
    elif last.endswith('s'):
        last = last[:-1]
    parts = ([head] if rest else []) + rest[:-1] + [last] if rest else [last]
    return pascal('_'.join(parts))


def camel(text: str) -> str:
    head, *rest = text.split('_')
    return head + ''.join(part.capitalize() for part in rest)


def java_type(ctype: str) -> str:
    if ctype.startswith(('CHAR', 'VARCHAR', 'TEXT')):
        return 'String'
    if ctype == 'INT':
        return 'Integer'
    if ctype == 'BIGINT':
        return 'Long'
    if ctype.startswith('DECIMAL'):
        return 'BigDecimal'
    if ctype == 'BOOLEAN':
        return 'Boolean'
    if ctype.startswith('DATETIME'):
        return 'Instant'
    if ctype == 'JSON':
        return 'JsonNode'
    raise SystemExit('未支持的类型: ' + ctype)


def enum_values(table: str, column: str, tables: dict) -> list[str]:
    for expr in tables[table]['checks']:
        m = re.match(r'^(\w+) IN \((.*)\)$', expr)
        if m and m.group(1) == column:
            return [v.strip().strip("'") for v in m.group(2).split(',')]
    raise SystemExit(f'找不到枚举约束: {table}.{column}')


def package_of(table: str, suffix: str) -> str:
    module = MODULES[table]
    if module == 'reliability':
        return f'com.repopilot.common.reliability.{suffix}'
    return f'com.repopilot.modules.{module}.{suffix}'


def build_enums(tables: dict) -> tuple[dict[str, str], dict[tuple[str, str], str]]:
    enum_of: dict[tuple[str, str], str] = {}
    files: dict[str, str] = {}
    for enum_name, pairs in ENUMS.items():
        values = enum_values(pairs[0][0], pairs[0][1], tables)
        for table, column in pairs:
            if enum_values(table, column, tables) != values:
                raise SystemExit(f'{enum_name}: {table}.{column} 取值不一致')
            enum_of[(table, column)] = enum_name
        annotations = '\n'.join(
            f'@DictionaryCheck(table = "{t}", column = "{c}")' for t, c in pairs)
        constants = ',\n'.join(f'    {v}' for v in values)
        pairs_doc = ' / '.join(f'{t}.{c}' for t, c in pairs)
        files[enum_name] = (
            'package com.repopilot.common.state;\n\n'
            '/**\n'
            f' * {{@code {pairs_doc}}} 的取值集合。\n'
            ' *\n'
            f' * <p>必须与数据库 CHECK 完全一致：{", ".join(values)}。\n'
            ' * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，\n'
            ' * 改这里而不改字典会立刻失败，反之亦然。\n'
            ' */\n'
            f'{annotations}\n'
            f'public enum {enum_name} {{\n\n'
            f'{constants};\n'
            '}\n')
    return files, enum_of


def build_entity(name: str, table: dict, enum_of: dict) -> tuple[str, str]:
    cls = singular(name)
    package = package_of(name, 'entity')
    fields = []
    java_types: set[str] = set()
    enum_types: set[str] = set()
    for col in table['columns']:
        ftype = enum_of.get((name, col['name'])) or java_type(col['type'])
        java_types.add(ftype)
        if (name, col['name']) in enum_of:
            enum_types.add(ftype)
        fields.append((camel(col['name']), ftype, col['description']))

    declarations = '\n'.join(
        f'    /** {desc} */\n    private {ftype} {field};' for field, ftype, desc in fields)
    accessors = []
    for field, ftype, _ in fields:
        cap = field[0].upper() + field[1:]
        accessors.append(
            f'    public {ftype} get{cap}() {{\n'
            f'        return {field};\n'
            '    }\n\n'
            f'    public void set{cap}({ftype} {field}) {{\n'
            f'        this.{field} = {field};\n'
            '    }')

    imports = []
    if enum_types:
        imports.append('import com.repopilot.common.state.*;')
    if 'JsonNode' in java_types:
        imports.append('import com.fasterxml.jackson.databind.JsonNode;')
    if 'BigDecimal' in java_types:
        imports.append('import java.math.BigDecimal;')
    if 'Instant' in java_types:
        imports.append('import java.time.Instant;')
    import_block = ''.join(line + '\n' for line in imports)
    if import_block:
        import_block += '\n'

    source = (
        f'package {package};\n\n'
        f'{import_block}'
        '/**\n'
        f' * {table["description"]}。\n'
        ' *\n'
        f' * <p>对应表 {{@code {name}}}，仅用于持久化，不作为 API 响应直接返回：\n'
        ' * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。\n'
        ' */\n'
        f'public class {cls} {{\n\n'
        f'{declarations}\n\n'
        f'{chr(10).join(chr(10) + a for a in accessors)}\n'
        '}\n')
    return f'{package.replace(".", "/")}/{cls}.java', source


def build_mapper(name: str, table: dict, enum_of: dict) -> tuple[str, str]:
    cls = singular(name)
    package = package_of(name, 'mapper')
    entity = f'{package_of(name, "entity")}.{cls}'
    writable = [c for c in table['columns'] if not c.get('generated_expression')]
    columns = ', '.join(c['name'] for c in writable)

    def param(col: dict) -> str:
        field = camel(col['name'])
        if col['type'] == 'CHAR(26)':
            return f'#{{{field},typeHandler={ULID_HANDLER}}}'
        return f'#{{{field}}}'

    values = ',\n                   '.join(param(c) for c in writable)
    select_cols = ',\n                   '.join(c['name'] for c in table['columns'])
    pk = table['primary_key']
    if pk == ['id']:
        signature = f'Optional<{cls}> findById(@Param("id") String id);'
        where = 'WHERE id = #{id}'
    else:
        args = ', '.join(f'@Param("{camel(c)}") String {camel(c)}' for c in pk)
        signature = f'Optional<{cls}> findById({args});'
        where = 'WHERE ' + '\n                      AND '.join(
            f'{c} = #{{{camel(c)}}}' for c in pk)

    source = (
        f'package {package};\n\n'
        f'import {entity};\n'
        'import org.apache.ibatis.annotations.Insert;\n'
        'import org.apache.ibatis.annotations.Mapper;\n'
        'import org.apache.ibatis.annotations.Param;\n'
        'import org.apache.ibatis.annotations.Select;\n\n'
        'import java.util.Optional;\n\n'
        '/**\n'
        f' * {{@code {name}}} 的基础读写。\n'
        ' *\n'
        ' * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更\n'
        ' * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。\n'
        ' * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。\n'
        ' */\n'
        '@Mapper\n'
        f'public interface {cls}Mapper {{\n\n'
        '    @Insert("""\n'
        f'            INSERT INTO {name} ({columns})\n'
        f'            VALUES ({values})\n'
        '            """)\n'
        f'    int insert({cls} entity);\n\n'
        '    @Select("""\n'
        f'            SELECT {select_cols}\n'
        f'              FROM {name}\n'
        f'              {where}\n'
        '            """)\n'
        f'    {signature}\n'
        '}\n')
    return f'{package.replace(".", "/")}/{cls}Mapper.java', source


def main() -> int:
    data = json.loads(DICT.read_text(encoding='utf-8'))
    tables = data['tables']
    missing = [t for t in tables if t not in MODULES]
    if missing:
        raise SystemExit('未分配模块: ' + ', '.join(missing))

    enum_files, enum_of = build_enums(tables)
    files: dict[str, str] = {}
    for enum_name, source in enum_files.items():
        files[f'com/repopilot/common/state/{enum_name}.java'] = source
    for name, table in tables.items():
        path, source = build_entity(name, table, enum_of)
        files[path] = source
        path, source = build_mapper(name, table, enum_of)
        files[path] = source

    if '--check' in sys.argv:
        stale = [p for p, s in files.items()
                 if not (SRC / p).exists() or (SRC / p).read_text(encoding='utf-8') != s]
        if stale:
            print('以下文件与字典不一致：' + ', '.join(sorted(stale)[:8]), file=sys.stderr)
            return 1
        print(f'OK：{len(files)} 个 Java 文件与字典一致')
        return 0

    for relative, source in files.items():
        path = SRC / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8', newline='\n')
    print(f'written {len(files)} files')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
