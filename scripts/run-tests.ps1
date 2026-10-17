<#
.SYNOPSIS
    Exécute la suite complète de tests sur la base PostgreSQL dédiée (workflow_test).

.DESCRIPTION
    Conforme aux principes de sécurité P9 et à la procédure du socle SQL :
    les mots de passe sont lus depuis les fichiers temporaires locaux sécurisés
    ($env:TEMP\wf_app.pwd et $env:TEMP\wf_owner.pwd) et ne sont jamais inscrits
    en clair dans le code, les arguments de ligne de commande ni l'historique shell.

.PARAMETER TestPattern
    Motif optionnel pour exécuter un sous-ensemble de tests (ex: WorkflowPersistenceTest).

.EXAMPLE
    ./scripts/run-tests.ps1
    ./scripts/run-tests.ps1 -TestPattern WorkflowPersistenceTest
#>
[CmdletBinding()]
param(
    [string]$TestPattern = ""
)

$ErrorActionPreference = "Stop"

$appPwdFile = Join-Path $env:TEMP "wf_app.pwd"
$ownerPwdFile = Join-Path $env:TEMP "wf_owner.pwd"

if (-not (Test-Path $appPwdFile) -or -not (Test-Path $ownerPwdFile)) {
    Write-Error "Fichiers de mots de passe introuvables ($appPwdFile ou $ownerPwdFile). Veuillez initialiser les secrets temporaires conformément à docs/socle-sql.md."
    exit 1
}

$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/workflow_test"
$env:SPRING_DATASOURCE_USERNAME = "workflow_app"
$env:SPRING_DATASOURCE_PASSWORD = (Get-Content $appPwdFile -Raw).Trim()
$env:FLYWAY_OWNER_USER = "workflow_owner"
$env:FLYWAY_OWNER_PASSWORD = (Get-Content $ownerPwdFile -Raw).Trim()

Write-Host "Lancement des tests sur PostgreSQL 17.2 (base workflow_test)..." -ForegroundColor Cyan

if ($TestPattern) {
    .\mvnw.cmd test -Dtest=$TestPattern
} else {
    .\mvnw.cmd test
}
