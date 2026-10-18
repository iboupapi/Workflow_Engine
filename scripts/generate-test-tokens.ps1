<#
.SYNOPSIS
    Génère des jetons JWT de test (agent, citoyen, administrateur) pour l'émetteur HMAC local.

.DESCRIPTION
    Fabrique trois jetons HS256 signés avec le secret de l'émetteur de test, afin d'exercer
    l'API sans attendre l'annuaire réel :
      - agent     : rôle AGENT_BUREAU (démarre le circuit sur l'étape AGENT_BUREAU) ;
      - citoyen   : rôle CITOYEN (E-Service) ;
      - admin     : administrateur nommé (identité préfixée « nomEmetteur:sub » à déclarer dans auth.admin.subjects).
    RÈGLE DE SÉCURITÉ : aucun secret n'est écrit dans un fichier. Le secret est lu dans la
    variable d'environnement TEST_ISSUER_SECRET (jamais fourni en dur, jamais journalisé).

.PARAMETER Issuer
    Valeur de la revendication 'iss' (doit correspondre à auth.jwt.issuers.<nom>.issuer).

.PARAMETER Audience
    Valeur de 'aud' (doit correspondre à auth.jwt.issuers.<nom>.audience).

.PARAMETER AdminSubject
    Sujet (sub) de l'administrateur ; l'identité à déclarer dans auth.admin.subjects est
    « nomEmetteur:sub » (voir -IssuerName).

.PARAMETER IssuerName
    Clé de configuration de l'émetteur (nom sous auth.jwt.issuers.<nom>) ; sert à composer
    l'identité préfixée « nomEmetteur:sub » (SEC-03) à déclarer comme administrateur.

.PARAMETER LifetimeSeconds
    Durée de validité des jetons (défaut 3600 s).

.EXAMPLE
    $env:TEST_ISSUER_SECRET = "jeton-test-local-32-octets-min!!"
    ./scripts/generate-test-tokens.ps1
#>
[CmdletBinding()]
param(
    [string]$Issuer = "https://test.mfp.local",
    [string]$Audience = "workflow-api",
    [string]$AdminSubject = "admin-01",
    [string]$IssuerName = "test",
    [int]$LifetimeSeconds = 3600
)

$ErrorActionPreference = "Stop"

$secret = $env:TEST_ISSUER_SECRET
if ([string]::IsNullOrEmpty($secret)) {
    Write-Error "TEST_ISSUER_SECRET doit être défini (secret HMAC de l'émetteur de test). Aucune valeur par défaut n'est fournie (jamais de mot de passe dans un fichier)."
    exit 1
}

function ConvertTo-Base64Url([byte[]]$Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-Hs256Jwt {
    param(
        [string]$Iss,
        [string]$Aud,
        [string]$Sub,
        [string[]]$Roles
    )
    $now = [int][double]::Parse((Get-Date -UFormat %s))
    $exp = $now + $LifetimeSeconds
    $rolesJson = ($Roles | ForEach-Object { '"' + ($_ -replace '"', '\"') + '"' }) -join ','
    $headerJson = '{"alg":"HS256","typ":"JWT"}'
    $payloadJson = '{"iss":"' + $Iss + '","aud":"' + $Aud + '","sub":"' + $Sub + '","roles":[' + $rolesJson + '],"iat":' + $now + ',"exp":' + $exp + '}'

    $header = ConvertTo-Base64Url ([System.Text.Encoding]::UTF8.GetBytes($headerJson))
    $payload = ConvertTo-Base64Url ([System.Text.Encoding]::UTF8.GetBytes($payloadJson))
    $signingInput = "$header.$payload"

    $hmac = [System.Security.Cryptography.HMACSHA256]::new([System.Text.Encoding]::UTF8.GetBytes($secret))
    try {
        $signature = ConvertTo-Base64Url ($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($signingInput)))
    } finally {
        $hmac.Dispose()
    }
    return "$signingInput.$signature"
}

$agentToken = New-Hs256Jwt -Iss $Issuer -Aud $Audience -Sub "agent-01" -Roles @("AGENT_BUREAU")
$citizenToken = New-Hs256Jwt -Iss $Issuer -Aud $Audience -Sub "citoyen-01" -Roles @("CITOYEN")
$adminToken = New-Hs256Jwt -Iss $Issuer -Aud $Audience -Sub $AdminSubject -Roles @("ADMIN")

Write-Output "# Jetons de test émis par '$Issuer' (audience '$Audience'), valides $LifetimeSeconds s."
Write-Output "# Prérequis : auth.jwt.issuers.<nom>.hmac-secret = TEST_ISSUER_SECRET, et auth.admin.subjects contient '$IssuerName`:$AdminSubject' (identité préfixée par l'émetteur, SEC-03)."
Write-Output ""
Write-Output "AGENT_JWT=$agentToken"
Write-Output "CITIZEN_JWT=$citizenToken"
Write-Output "ADMIN_JWT=$adminToken"
