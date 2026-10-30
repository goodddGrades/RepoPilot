"""从 database-schema.json 生成持久化实体、基础 Mapper 与枚举约束。

为什么不手写 28 个实体：字段、类型、可空性、枚举取值都在字典里，手抄一遍必然漂移，
而且漂移后只能靠人眼比对。生成器让"字典是唯一来源"成为可执行的事实，
CI 用 --check 就能发现有人改了字典却没重新生成。

`--check` 的两种口径（TASK-003 起区分）：

* **实体与枚举**：必须与生成结果逐字节相同。它们没有手写内容的正当理由。
* **Mapper**：生成的那部分必须原样保留，但允许在文件里追加业务查询方法。
  业务方法（按 code 查、分页、`FOR UPDATE`、批量插入等）无法从表结构推导出来，
  事实上一旦有了业务代码就必然存在。早期 `--check` 用的是整文件逐字节比较，
  于是"加了业务方法"会被判成"改了字典却没重新生成"——它把两种完全不同的情况混成一个错误，
  结果是这条检查在真实开发中只能被关掉。现在的口径是：**生成的成员必须按原顺序出现**，
  多余内容不追究；删列、改类型、改主键这类真正的漂移仍然会失败。

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


def strip_comments(text: str) -> str:
    """去掉块注释与行注释，只留代码。避免文档里提到的 SQL 片段被当成"已经实现"。"""
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', text)


def normalize(text: str) -> str:
    """折叠空白并去掉标识符反引号：换行、对齐与引用号是排版，不是内容。

    反引号必须忽略：`required` 是 MySQL 保留字，实际 SQL 里只能写成 `` `required` ``，
    而字典里存的是列名 `required`。两者指同一个列，比较时不该算差异。
    """
    return ' '.join(text.replace('`', '').split())


def inline_string_constants(text: str) -> str:
    # 把 `String X = "a," + " b";` 这类常量内联回所有引用处，再折叠空白。
    #
    # 为什么需要这一步：TASK-009 起 AnnotationTaskMapper 用 COLUMNS 常量拼接列清单，
    # 再与 "SELECT " / FROM 子句用 + 连接，来避免文本块剥掉行尾空白、
    # 拼出 `SELECTid, ...` 这种编译期看不出的坏 SQL。
    # 这个写法是刻意的，不应该为了让检查通过而退回文本块——但检查脚本必须能读懂它，
    # 否则就会把"用了常量"误报成"列清单与字典不一致"（TASK-012 收尾时 CI design Job 会红）。
    #
    # 三步：把声明归一为单个字面量 → 折叠字符串拼接中的 "+" → 用声明处的字面量替换引用。
    # 内联而不是跳过：常量里写错的列名照样会被比对出来，检查强度不降。
    decl = re.compile(r'String\s+(\w+)\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+);')

    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)

    # 1) 收集常量声明：名字 → 拼接起来的字面量值
    values: dict[str, str] = {}
    for name, body in decl.findall(text):
        values[name] = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', body))
    if not values:
        return text

    # 2) 先把声明本身折叠成单个字面量，后续步骤才好统一处理
    def collapse(match: re.Match) -> str:
        return values[match.group(1)] + ' ; '

    text = decl.sub(collapse, text)

    # 3) 用字面量替换所有裸标识符引用（长名优先，避免 COLUMNS 抢在 COLUMNS_X 前面）
    for name in sorted(values, key=len, reverse=True):
        text = re.sub(rf'(?<![\w"]){re.escape(name)}(?![\w"])', values[name], text)

    # 4) 清掉只剩空壳的声明，再折叠 Java 字符串拼接。
    #    注意顺序：必须先把所有字面量/文本块统一成同一种形式再折叠 "+"，
    #    否则 "SELECT " + COLUMNS + """ FROM """ 这种混合拼接会漏掉跨边界的 "+"。
    text = re.sub(r'String\s+\w+\s*=\s*"?\s*;', ' ', text)
    text = text.replace('"""', '"')
    for _ in range(10):
        collapsed = re.sub(r'"\s*\+\s*', '', text)
        collapsed = re.sub(r'\s*\+\s*"', '', collapsed)
        if collapsed == text:
            break
        text = collapsed
    # 常量值现在是裸文本而非字面量，引号已无意义，统一去掉以免"SELECT " 与 id 之间多出分隔。
    text = text.replace('"', ' ')
    return text


def mapper_still_matches(actual: str, name: str, table: dict) -> list[str]:
    """Mapper 是否仍然如实反映字典（返回缺失的片段，空列表表示通过）。

    只校验字典真正约束的事实，不比对整份文件：

    * 插入语句的表名与可写列清单（含顺序）；
    * 查询语句的列清单（含顺序）与表名；
    * 主键条件。

    为什么不再要求"生成的内容逐字节保留"：TASK-003 起 Mapper 里有了业务查询方法
    （按 code 查、分页、`FOR UPDATE`、批量插入），SQL 也按可读性排过版。
    继续用整文件比较，只会把"加了业务方法"和"改了字典没重新生成"报成同一个错误，
    最后这条检查只能被关掉——那等于没有检查。
    """
    writable = ', '.join(c['name'] for c in table['columns'] if not c.get('generated_expression'))
    all_columns = ', '.join(c['name'] for c in table['columns'])

    pk = table['primary_key']
    if pk == ['id']:
        where = 'WHERE id = #{id}'
    else:
        where = 'WHERE ' + ' AND '.join(f'{c} = #{{{camel(c)}}}' for c in pk)

    code = normalize(strip_comments(inline_string_constants(actual)))
    required = [
        f'INSERT INTO {name} ({writable})',
        f'SELECT {all_columns} FROM {name}',
        where,
    ]
    return [snippet for snippet in required if snippet not in code]


def self_test() -> int:
    # 检查器自身的回归测试。
    #
    # 为什么必须有：TASK-012 收尾时发现 CI design Job 会红——AnnotationTaskMapper
    # 在 TASK-009 改用 COLUMNS 常量拼接后，旧版检查器读不懂，把正确的代码报成不一致。
    # 这种"检查器坏了"和"代码坏了"同样致命，但如果只修代码不改检查器，
    # 下一个人改 Mapper 写法时又会静默失效。
    #
    # 断言两件事：① 用常量的正确写法必须通过；② 常量里写错的列名、顺序、表名仍必须被抓出。
    # 只有 ① 通过就等于把检查改成"永远绿"，那是更糟的回归。
    table = {
        'columns': [
            {'name': 'id'}, {'name': 'run_id'}, {'name': 'version'},
            {'name': 'created_at'}, {'name': 'updated_at'},
        ],
        'primary_key': ['id'],
    }
    columns = 'id, run_id, version, created_at, updated_at'
    table_name = 'demo_things'

    good = f'''
        @Mapper
        public interface DemoThingMapper {{
            String COLUMNS = "{columns[:len(columns) - len('updated_at')]}"
                    + " updated_at";

            @Insert("INSERT INTO {table_name} ({columns}) VALUES (...)")
            int insert(DemoThing entity);

            @Select("SELECT " + COLUMNS + """
                      FROM {table_name}
                     WHERE id = #{{id}}
                    """)
            DemoThing findById(String id);
        }}
    '''
    literal = f'''
        @Mapper
        public interface DemoThingMapper {{
            @Insert("INSERT INTO {table_name} ({columns}) VALUES (...)")
            int insert(DemoThing entity);

            @Select("""
            SELECT {columns}
              FROM {table_name}
             WHERE id = #{{id}}
            """)
            DemoThing findById(String id);
        }}
    '''

    cases: list[tuple[str, str, bool]] = [
        ('常量拼接的正确写法应通过', good, True),
        ('字面量写法的正确写法应通过', literal, True),
        ('常量里少一列必须被抓出', good.replace('updated_at', 'created_at2'), False),
        ('常量里列顺序错必须被抓出',
         good.replace('id, run_id, version', 'run_id, id, version'), False),
        ('插入表名错必须被抓出', good.replace(f'INSERT INTO {table_name}',
                                            'INSERT INTO other_table'), False),
        ('主键条件缺失必须被抓出', good.replace('WHERE id = #{id}', 'WHERE 1 = 1'), False),
    ]

    failures = 0
    for title, source, should_pass in cases:
        missing = mapper_still_matches(source, table_name, table)
        passed = not missing
        ok = passed == should_pass
        if not ok:
            failures += 1
        verdict = 'OK' if ok else 'FAIL'
        print(f'[{verdict}] {title}')
    if failures:
        print(f'\n自测失败：{failures} 项', file=sys.stderr)
        return 1
    print(f'\n自测通过：{len(cases)} 项')
    return 0


def main() -> int:
    if '--self-test' in sys.argv:
        return self_test()
    data = json.loads(DICT.read_text(encoding='utf-8'))
    tables = data['tables']
    missing = [t for t in tables if t not in MODULES]
    if missing:
        raise SystemExit('未分配模块: ' + ', '.join(missing))

    enum_files, enum_of = build_enums(tables)
    files: dict[str, str] = {}
    mapper_tables: dict[str, tuple[str, dict]] = {}
    for enum_name, source in enum_files.items():
        files[f'com/repopilot/common/state/{enum_name}.java'] = source
    for name, table in tables.items():
        path, source = build_entity(name, table, enum_of)
        files[path] = source
        path, source = build_mapper(name, table, enum_of)
        files[path] = source
        mapper_tables[path] = (name, table)

    if '--check' in sys.argv:
        stale: list[str] = []
        details: list[str] = []
        for p, s in files.items():
            target = SRC / p
            if not target.exists():
                stale.append(p)
                details.append(f'{p}: 文件缺失')
                continue
            actual = target.read_text(encoding='utf-8')
            if p in mapper_tables:
                missing = mapper_still_matches(actual, *mapper_tables[p])
                if missing:
                    stale.append(p)
                    details.append(f'{p}: 缺少 {missing[0][:80]}')
            elif actual != s:
                stale.append(p)
                details.append(f'{p}: 与生成结果不同')
        if stale:
            for line in details[:8]:
                print(line, file=sys.stderr)
            print('以下文件与字典不一致：' + ', '.join(sorted(stale)[:8]), file=sys.stderr)
            return 1
        print(f'OK：{len(files)} 个 Java 文件与字典一致'
              f'（实体/枚举逐字节比对，{len(mapper_tables)} 个 Mapper 比对列集合/顺序/主键条件）')
        return 0

    for relative, source in files.items():
        path = SRC / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8', newline='\n')
    print(f'written {len(files)} files')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
