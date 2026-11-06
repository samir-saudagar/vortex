#!/usr/bin/env python3
"""Read-only application-table and local simulator-state fingerprints; no record contents."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PSQL = ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -qAt -v ON_ERROR_STOP=1']

def query(sql):
    return subprocess.run(PSQL, input=sql, text=True, capture_output=True,
                          cwd=ROOT, check=True).stdout.strip()

def snapshot():
    tables = query("SELECT tablename FROM pg_tables WHERE schemaname='vortex' ORDER BY tablename").splitlines()
    selects = []
    for table in tables:
        identifier = '"' + table.replace('"', '""') + '"'
        literal = "'" + table.replace("'", "''") + "'"
        selects.append(f"SELECT json_build_object('table',{literal},'rows',count(*),'hash',"
                       f"md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')))::text "
                       f"FROM vortex.{identifier} t")
    rows = query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' +
                 '\nUNION ALL\n'.join(selects) + ';\nCOMMIT;') if selects else ''
    local = ROOT / 'simulator/.local'
    return {'tables': [json.loads(line) for line in rows.splitlines()],
            'simulator_local': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in sorted(local.rglob('*')) if p.is_file()}}

if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: phase6-preservation.py <output.json>')
    Path(sys.argv[1]).write_text(json.dumps(snapshot(), indent=2) + '\n')
