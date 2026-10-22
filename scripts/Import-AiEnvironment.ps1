# Only this project's ignored AI credentials are loaded. Never print the settings.
$ErrorActionPreference='Stop'
$aiRoot=Split-Path $PSScriptRoot -Parent
$aiPath=Join-Path $aiRoot '.local/ai-secrets.json'
if(Test-Path -LiteralPath $aiPath){
  try{$aiSettings=Get-Content -Raw -Encoding UTF8 -LiteralPath $aiPath|ConvertFrom-Json}catch{throw 'Invalid local AI settings; details suppressed.'}
  $env:AI_LLM_API_KEY=[string]$aiSettings.apiKey
  $env:AI_LLM_BASE_URL=[string]$aiSettings.baseUrl
  $env:AI_LLM_MODEL=[string]$aiSettings.model
  $env:AI_SERVICE_TOKEN=[string]$aiSettings.serviceToken
  $env:MFG_AI_SERVICE_TOKEN=[string]$aiSettings.serviceToken
}
if(!$env:AI_ERP_BASE_URL){$env:AI_ERP_BASE_URL='http://127.0.0.1:8081'}
if(!$env:MFG_AI_AGENT_URL){$env:MFG_AI_AGENT_URL='http://127.0.0.1:8091'}
