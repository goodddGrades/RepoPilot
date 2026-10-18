"""验证设计字典、文档链接和JSON Schema样例；不运行应用或MySQL。"""
from pathlib import Path
from datetime import datetime
from copy import deepcopy
import json
import re
import sys
import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
checks = 0

def check(condition, message):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)

def load(path):
    return json.loads((ROOT/path).read_text(encoding='utf-8'))

fmt = FormatChecker()

# 真实仓库包含依赖目录与构建产物，其中自带大量第三方 README；
# 它们不属于设计文档范围，检查时跳过，避免在 node_modules 等目录误报断链。
SKIP_DIRS = {
    '.git', '.idea', '.vscode', 'node_modules', 'target', 'dist', 'build',
    '.venv', 'venv', '.tools-venv', '__pycache__', 'logs',
}


def iter_markdown():
    for path in sorted(ROOT.rglob('*.md')):
        if any(part in SKIP_DIRS for part in path.relative_to(ROOT).parts):
            continue
        yield path


@fmt.checks('date-time', raises=(ValueError, TypeError))
def date_time(value):
    if not isinstance(value, str):
        return True
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})', value):
        return False
    return datetime.fromisoformat(value.replace('Z', '+00:00')).tzinfo is not None

# 关联表以业务键作复合主键：它没有独立生命周期，ULID 代理键只会多一份索引。
# 其余业务表一律 ULID 单列主键。
ASSOCIATION_PK = {'evaluation_batch_runs': ['batch_id', 'run_id']}

def main():
    global checks
    tables = load('docs/contracts/database-schema.json')['tables']
    check(len(tables)==28, 'table count')
    for name,t in tables.items():
        columns = {c['name']:c for c in t['columns']}
        check(len(columns)==len(t['columns']), 'duplicate column: '+name)
        check(len(t['primary_key'])==len(set(t['primary_key'])), 'primary key repeats column: '+name)
        if name in ASSOCIATION_PK:
            check(t['primary_key']==ASSOCIATION_PK[name], 'association primary key: '+name)
            check(all(not columns[c]['nullable'] for c in t['primary_key']), 'association pk nullable: '+name)
        else:
            check(t['primary_key']==['id'], 'primary key: '+name)
            check(columns['id']['type']=='CHAR(26)' and not columns['id']['nullable'], 'ULID: '+name)
        for key in t['unique_keys']+t['indexes']+[t['primary_key']]:
            check(all(c in columns for c in key), 'key column: '+name)
        # 同一列组合不得既是多条 UNIQUE 又重复声明为普通索引；与主键相同的前缀索引也视为冗余
        declared = [tuple(k) for k in t['unique_keys']+t['indexes']]
        check(len(declared)==len(set(declared)), 'duplicate key declaration: '+name)
        check(tuple(t['primary_key']) not in declared, 'index duplicates primary key: '+name)
        for fk in t['foreign_keys']:
            parent = tables[fk['references_table']]
            pcols = {c['name']:c for c in parent['columns']}
            check(len(fk['columns'])==len(fk['references_columns']), 'FK arity: '+name)
            check(fk['references_columns'] in parent['unique_keys']+[parent['primary_key']], 'FK unique: '+name)
            for c,pc in zip(fk['columns'],fk['references_columns']):
                check(c in columns and pc in pcols, 'FK columns: '+name)
                check(columns[c]['type']==pcols[pc]['type'], 'FK type: '+name)
                check(not pcols[pc]['nullable'], 'FK parent key nullable: '+name+'.'+pc)
            check(fk['on_delete']=='RESTRICT', 'FK deletion: '+name)
            # 每条外键必须有显式声明的左前缀索引，禁止依赖 InnoDB 隐式建索引
            check(any(k[:len(fk['columns'])]==fk['columns'] for k in [t['primary_key']]+t['unique_keys']+t['indexes']),
                  'FK prefix index missing: '+name+' '+str(fk['columns']))
            # 复合外键中任一列可空会让 MATCH SIMPLE 跳过整条校验，必须显式登记为可选关系
            if len(fk['columns'])>1:
                nullable=[c for c in fk['columns'] if columns[c]['nullable']]
                check(bool(nullable)==bool(fk.get('optional')),
                      'composite FK optional flag mismatch: '+name+' '+str(fk['columns']))
                if nullable:
                    check(bool(fk.get('optional_reason')), 'optional FK reason missing: '+name)
        for expression in t['checks']:
            words=set(re.findall(r'[A-Za-z_][A-Za-z0-9_]*',re.sub(r"'[^']*'",'',expression)))
            check(words <= set(columns)|{'IS','NULL','NOT','OR','AND','IN'}, 'CHECK references: '+name+' '+expression)

    # 批次语义必须在数据库层闭合：Run 集合固定、且批次与 Run 同实验
    batch_runs = tables['evaluation_batch_runs']
    check([c['name'] for c in batch_runs['columns']]==['batch_id','run_id','experiment_id','created_at'], 'batch_runs columns')
    for fk in batch_runs['foreign_keys']:
        check('experiment_id' in fk['columns'] and 'experiment_id' in fk['references_columns'],
              'batch_runs same experiment: '+str(fk['columns']))
    check(any(f['columns']==['batch_id','run_id'] and f['references_table']=='evaluation_batch_runs'
              for f in tables['evaluation_jobs']['foreign_keys']), 'job must reference batch run set')
    check(['id','experiment_id'] in tables['evaluation_batches']['unique_keys'], 'batch composite key')
    check(['id','experiment_id'] in tables['evaluation_runs']['unique_keys'], 'run composite key')
    check(any(f['columns']==['parent_batch_id','experiment_id'] and f['references_table']=='evaluation_batches'
              for f in tables['evaluation_batches']['foreign_keys']), 'parent batch must stay in experiment')
    # 每个实验最多一个 INITIAL 批次必须由数据库保证：生成列 + 唯一键，而不是应用层先查后插
    batches = tables['evaluation_batches']
    bcols = {c['name']:c for c in batches['columns']}
    check('initial_experiment_id' in bcols, 'initial batch generated column')
    check(bool(bcols['initial_experiment_id'].get('generated_expression')), 'initial batch expression')
    check("INITIAL" in bcols['initial_experiment_id']['generated_expression'], 'initial batch expression mentions INITIAL')
    check(bcols['initial_experiment_id']['nullable'], 'initial batch column must allow NULL for non-INITIAL rows')
    check(['initial_experiment_id'] in batches['unique_keys'], 'initial batch unique key')
    for name,t in tables.items():
        for c in t['columns']:
            if c.get('generated_expression'):
                check(c['nullable'], 'generated column must be nullable for multi-NULL uniqueness: '+name+'.'+c['name'])
                check(c['name'] not in t['primary_key'], 'generated column in primary key: '+name+'.'+c['name'])

    validators={}
    for path in sorted((ROOT/'docs/contracts').glob('*.schema.json')):
        schema=json.loads(path.read_text())
        Draft202012Validator.check_schema(schema)
        check(schema['$schema'].endswith('/2020-12/schema'), 'schema dialect')
        validators[path.name.removesuffix('.schema.json')]=Draft202012Validator(schema,format_checker=fmt)
    samples={
        'dataset-case': json.loads((ROOT/'examples/dataset-cases.jsonl').read_text().splitlines()[0]),
        'offline-run': json.loads((ROOT/'examples/offline-runs.jsonl').read_text().splitlines()[0]),
        'trace-event':load('examples/trace-event.json'),
        'event-envelope':load('examples/event-envelope.json')
    }
    for name,obj in samples.items():
        check(not list(validators[name].iter_errors(obj)), 'positive sample: '+name)
    negative=[]
    def changed(name,key,value):
        obj=deepcopy(samples[name]);obj[key]=value;negative.append((name,obj))
    for name in samples:
        changed(name,'schemaVersion',2)
        changed(name,'unknownField',True)
    changed('trace-event','traceId','0'*32)
    changed('trace-event','sequenceNo',-1)
    changed('trace-event','eventType','UNREGISTERED')
    changed('trace-event','occurredAt','2026-10-17T04:00:00')
    changed('offline-run','repeatIndex',0)
    changed('offline-run','sourceRecordId','')
    changed('event-envelope','aggregateType','EXPERIMENT')
    changed('event-envelope','payload',{'jobId':samples['event-envelope']['aggregateId']})
    changed('dataset-case','input','plain text')
    obj=deepcopy(samples['offline-run']);del obj['sourceSystem'];negative.append(('offline-run',obj))
    for name,obj in negative:
        check(bool(list(validators[name].iter_errors(obj))), 'negative sample accepted: '+name)

    common=yaml.safe_load((ROOT/'docs/contracts/openapi-common.yaml').read_text())
    check(common['openapi']=='3.1.0', 'OpenAPI version')
    check(common['paths']=={}, 'components-only declaration')
    for key in ('Success','Error','Page','Ulid'):
        check(key in common['components']['schemas'], 'OpenAPI component: '+key)

    for path in iter_markdown():
        body=path.read_text()
        check(body.count('```')%2==0, 'unclosed fence: '+str(path))
        for target in re.findall(r'\]\(([^)]+)\)',body):
            if re.match(r'\w+://',target) or target.startswith('#'):
                continue
            check((path.parent/target.split('#')[0]).exists(), 'broken link: '+str(path)+' '+target)
    for i in range(1,13):
        check((ROOT/'tasks'/f'TASK-{i:03}.md').exists(),'missing task')
    dbtext=(ROOT/'docs/architecture/03-database-design.md').read_text()
    for name,t in tables.items():
        check('## '+name+'\n' in dbtext, 'dictionary table missing: '+name)
        for c in t['columns']:
            check('| '+c['name']+' | '+c['type']+' |' in dbtext, 'dictionary column missing')
    # 报告默认语义必须写死在契约里，不能随后台重评漂移
    api=(ROOT/'docs/architecture/04-api-contract.md').read_text()
    check('batchScope' in api and '默认返回初始批次' in api, 'report default batch semantics')
    # LATEST 不得静默跳过未完成或失败批次，否则"最新评测"会返回旧结论
    check('LATEST' in api and '最新创建' in api, 'LATEST means newest created batch')
    check('不得静默回退' in api, 'LATEST must not fall back silently')
    check('LATEST_COMPLETED' in api, 'latest completed scope must stay separate')

    print(json.dumps({'status':'PASS','checks':checks,'tables':len(tables),'json_schemas':len(validators),'positive_samples':len(samples),'negative_samples':len(negative),'scope':'设计文件静态检查；不代表MySQL迁移或应用验收'},ensure_ascii=False,indent=2))

if __name__=='__main__':
    try:
        main()
    except Exception as exc:
        print(json.dumps({'status':'FAIL','checks':checks,'error':str(exc)},ensure_ascii=False),file=sys.stderr)
        raise SystemExit(1)
