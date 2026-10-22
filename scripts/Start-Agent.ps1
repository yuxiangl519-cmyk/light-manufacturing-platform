param([switch]$Test)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-AiEnvironment.ps1')
if($Test){$env:AI_ERP_BASE_URL='http://127.0.0.1:18081'}
$aiRoot=Split-Path $PSScriptRoot -Parent
$python=Join-Path $aiRoot 'agent/.venv/Scripts/python.exe'
if(!(Test-Path -LiteralPath $python)){throw 'Create agent/.venv and pip install -r agent/requirements.lock.txt first.'}
& $python -m uvicorn app.main:app --app-dir (Join-Path $aiRoot 'agent') --host 127.0.0.1 --port 8091 --no-access-log
if($LASTEXITCODE -ne 0){throw 'Agent process stopped with an error.'}
