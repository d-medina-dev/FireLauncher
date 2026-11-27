# =====================================================================================
#  Fire Launcher - instalador automático para Windows
#  Conecta con el televisor por la red, instala el launcher y le da todos los permisos.
#  Se abre con doble clic en "Instalar.bat" (que lanza este archivo en PowerShell).
#  Compatible con Windows PowerShell 5.1 (Windows 10 y 11) y PowerShell 7.
# =====================================================================================

# ---------- Configuración para quien publica el proyecto ----------
# Repositorio de GitHub (usuario/repositorio). Si junto a este archivo no hay APK, se descarga
# de la última versión publicada (Releases), con estos nombres de archivo.
$Repo = 'd-medina-dev/FireLauncher'          # ejemplo: 'tu-usuario/FireLauncher'
$App  = @{ Nombre = 'Fire Launcher'; Apk = 'FireLauncher.apk'; Pkg = 'com.ejemplo.launcher'; Lite = $false }
# El otro launcher del proyecto: si también está instalado, el botón Inicio pasa a este.
$Otro = @{ Nombre = 'Fire Launcher Lite'; Pkg = 'com.ejemplo.launcherlite' }
$Clases = 'com.ejemplo.launcher'   # paquete de las clases Java (es el mismo en las dos apps)
$AdbUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
# -------------------------------------------------------------------

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { $Host.UI.RawUI.WindowTitle = 'Instalador de Fire Launcher' } catch { }
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:AdbExe = $null
$script:Dev = $null

# ---------- Mensajes ----------
function Titulo([string]$t) { Write-Host ''; Write-Host ('== ' + $t + ' ==') -ForegroundColor Cyan }
function Ok([string]$t)     { Write-Host ('  [OK] ' + $t) -ForegroundColor Green }
function Aviso([string]$t)  { Write-Host ('  [!]  ' + $t) -ForegroundColor Yellow }
function Falla([string]$t)  { Write-Host ('  [X]  ' + $t) -ForegroundColor Red }
function Info([string]$t)   { Write-Host ('       ' + $t) -ForegroundColor Gray }
function SiNo([string]$pregunta, [bool]$porDefecto) {
    $sufijo = ' (S/N)'
    if ($porDefecto) { $sufijo = ' (S/n)' } else { $sufijo = ' (s/N)' }
    $r = (Read-Host ($pregunta + $sufijo)).Trim()
    if ($r -eq '') { return $porDefecto }
    return ($r -match '^[sSyY]')
}

# ---------- ADB ----------
function Run-Adb([string[]]$A) {
    $out = & $script:AdbExe @A 2>&1 | Out-String
    return $out.Trim()
}
function Sh([string]$cmd) { return (Run-Adb @('-s', $script:Dev, 'shell', $cmd)) }

function Get-Adb {
    $local = Join-Path $Dir 'platform-tools\adb.exe'
    if (Test-Path $local) { return $local }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    Titulo 'Herramienta ADB'
    Write-Host 'Para comunicarse con el televisor hace falta ADB (Android Platform-Tools), de Google.'
    Write-Host 'Se descarga una sola vez desde el sitio oficial (unos 6 MB) y queda en esta carpeta.'
    Write-Host 'Al descargarla aceptas sus términos de uso:'
    Write-Host '  https://developer.android.com/studio/releases/platform-tools' -ForegroundColor Gray
    if (-not (SiNo '¿Descargar ADB ahora?' $true)) { return $null }
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Write-Host '  Descargando...'
        Invoke-WebRequest -Uri $AdbUrl -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath $Dir -Force
        Remove-Item $zip -ErrorAction SilentlyContinue
    } catch {
        Falla ('No se pudo descargar ADB: ' + $_.Exception.Message)
        Info 'Revisa la conexión a internet o descárgalo a mano y pon la carpeta "platform-tools" aquí.'
        return $null
    }
    if (Test-Path $local) { Ok 'ADB listo.'; return $local }
    Falla 'La descarga no trajo adb.exe.'
    return $null
}

# ---------- Conexión ----------
function Conectar([string]$target) {
    for ($i = 1; $i -le 6; $i++) {
        $r = Run-Adb @('connect', $target)
        Start-Sleep -Milliseconds 700
        $st = Run-Adb @('-s', $target, 'get-state')
        if ($st.Trim() -eq 'device') { return $true }
        if (($r + ' ' + $st) -match 'unauthorized|authenticate') {
            Aviso 'Mira la pantalla del televisor: debe aparecer «¿Permitir la depuración?».'
            Info  'Marca «Permitir siempre desde esta computadora» y pulsa Aceptar.'
            Read-Host '       Cuando lo aceptes, presiona Enter aquí' | Out-Null
            continue
        }
        if ($st -match 'offline') {
            Run-Adb @('disconnect', $target) | Out-Null
            Start-Sleep -Seconds 1
            continue
        }
        if ($i -eq 1) { Info ('Respuesta: ' + ($r -split "`n")[0]) }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Emparejar {
    Write-Host ''
    Write-Host 'En el televisor: Ajustes > Opciones para desarrolladores > Depuración inalámbrica >'
    Write-Host '«Vincular dispositivo con código». Ahí aparecen una IP con puerto y un código de 6 dígitos.'
    $pp = (Read-Host 'IP:puerto que muestra para VINCULAR (ej. 192.168.1.50:37215)').Trim()
    $code = (Read-Host 'Código de 6 dígitos').Trim()
    $r = Run-Adb @('pair', $pp, $code)
    if ($r -notmatch 'Successfully paired') { Falla ('No se pudo vincular: ' + ($r -split "`n")[0]); return $null }
    Ok 'Vinculado.'
    Write-Host 'Ahora, en la pantalla de «Depuración inalámbrica», mira «Dirección IP y puerto» (otro puerto distinto).'
    $cp = (Read-Host 'IP:puerto para CONECTAR (ej. 192.168.1.50:41893)').Trim()
    if (Conectar $cp) { return $cp }
    return $null
}

# ---------- APK ----------
function Get-Apk($app) {
    $p = Join-Path $Dir $app.Apk
    if (Test-Path $p) { return $p }
    $quiereLite = ($app.Pkg -like '*lite')
    $alt = Get-ChildItem -Path $Dir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Where-Object { (($_.Name -match 'lite') -eq $quiereLite) } | Select-Object -First 1
    if ($alt) { return $alt.FullName }
    if ($Repo) {
        $url = 'https://github.com/' + $Repo + '/releases/latest/download/' + $app.Apk
        Write-Host ('  Descargando ' + $app.Apk + ' de la última versión publicada...')
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -Uri $url -OutFile $p -UseBasicParsing
            if (Test-Path $p) { Ok 'APK descargado.'; return $p }
        } catch {
            Falla ('No se pudo descargar el APK: ' + $_.Exception.Message)
            Remove-Item $p -ErrorAction SilentlyContinue
        }
    }
    return $null
}

# ---------- Permisos ----------
$script:Resumen = @()
function Paso([string]$desc, [string]$cmd) {
    $r = Sh $cmd
    if ($r -match 'Exception|rror|not found|Unknown|denied|Failure|inaccessible|not a changeable') {
        Aviso ($desc + ': ' + ($r -split "`n")[0])
        $script:Resumen += ('[!] ' + $desc)
        return $false
    }
    Ok $desc
    $script:Resumen += ('[OK] ' + $desc)
    return $true
}
function Lista([string]$valor) {
    if (-not $valor -or $valor.Trim() -eq 'null') { return @() }
    return @($valor.Trim().Split(':') | Where-Object { $_ -ne '' })
}

# =====================================================================================
try {
    Clear-Host
    Write-Host ''
    Write-Host ('   ' + $App.Nombre.ToUpper() + ' - INSTALADOR') -ForegroundColor White
    Write-Host '   Instala el launcher en tu TV o TV box y le da todos los permisos.' -ForegroundColor Gray
    Write-Host ''
    Write-Host 'Antes de seguir, en el televisor:'
    Write-Host '  - Fire TV: Configuración > Mi Fire TV > Opciones para desarrolladores > Depuración ADB: ACTIVADA.'
    Write-Host '    (Si no ves «Opciones para desarrolladores»: Mi Fire TV > Acerca de > pulsa 7 veces el nombre del equipo.)'
    Write-Host '  - Android TV / Google TV: Ajustes > Sistema > Acerca de > pulsa 7 veces «Compilación del SO»;'
    Write-Host '    luego Opciones para desarrolladores > Depuración USB (o «Depuración inalámbrica»): ACTIVADA.'
    Write-Host '  - La PC y el televisor deben estar en la misma red (Wi-Fi o cable).'
    Write-Host '  - La IP del televisor está en: Fire TV > Mi Fire TV > Acerca de > Red; Android TV > Ajustes > Red.'

    # 1) ADB
    $script:AdbExe = Get-Adb
    if (-not $script:AdbExe) { Falla 'Sin ADB no se puede continuar.'; return }
    Run-Adb @('start-server') | Out-Null

    # 2) IP y conexión
    Titulo 'Conectar con el televisor'
    $ipFile = Join-Path $Dir 'ultima_ip.txt'
    $last = ''
    if (Test-Path $ipFile) { $last = (Get-Content $ipFile -ErrorAction SilentlyContinue | Select-Object -First 1) }
    while (-not $script:Dev) {
        $msg = 'Escribe la IP de tu TV o TV box (ej. 192.168.1.50)'
        if ($last) { $msg = $msg + ' [Enter = ' + $last + ']' }
        $ip = (Read-Host $msg).Trim()
        if ($ip -eq '' -and $last) { $ip = $last }
        if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}(:\d{2,5})?$') { Aviso 'Eso no parece una IP. Ejemplo: 192.168.1.50'; continue }
        $target = $ip
        if ($ip -notmatch ':\d+$') { $target = $ip + ':5555' }
        Write-Host ('  Conectando con ' + $target + '...')
        if (Conectar $target) { $script:Dev = $target; break }
        Falla ('No se pudo conectar con ' + $target + '.')
        Info 'Revisa: la IP es correcta, la depuración ADB está activada y ambos están en la misma red.'
        Info 'En Google TV y Android 11 o superior puede hacer falta vincular con código (Depuración inalámbrica).'
        if (SiNo '¿Vincular con código (Depuración inalámbrica)?' $false) {
            $p = Emparejar
            if ($p) { $script:Dev = $p; break }
        }
        if (-not (SiNo '¿Probar otra vez?' $true)) { return }
    }
    Set-Content -Path $ipFile -Value ($script:Dev -replace ':5555$', '') -Encoding ASCII
    Ok ('Conectado con ' + $script:Dev + '.')

    # 3) Equipo
    $man = Sh 'getprop ro.product.manufacturer'
    $model = Sh 'getprop ro.product.model'
    $sdkTxt = Sh 'getprop ro.build.version.sdk'
    $sdk = 0; [void][int]::TryParse(($sdkTxt -replace '\D', ''), [ref]$sdk)
    $memKb = 0
    $mi = Sh 'grep MemTotal /proc/meminfo'
    if ($mi -match '(\d+)\s*kB') { $memKb = [int64]$Matches[1] }
    $memGb = [math]::Round($memKb / 1048576, 1)
    $amazon = ($man -match 'amazon')
    Write-Host ('  Equipo: ' + $man + ' ' + $model + ' · Android API ' + $sdk + ' · RAM ' + $memGb + ' GB')
    if ($sdk -gt 0 -and $sdk -lt 25) { Falla 'Este equipo es demasiado antiguo: hace falta Android 7.1 (API 25) o superior.'; return }

    # 4) Este instalador es solo para esta app; según la memoria del equipo se da un consejo
    $app = $App
    $otro = $Otro
    $Pkg = $App.Pkg
    if (-not $App.Lite -and $memKb -gt 0 -and $memKb -lt 1700000) {
        Aviso ('Este equipo tiene ' + $memGb + ' GB de RAM. En equipos así, ' + $Otro.Nombre + ' funciona más fluido.')
        if (-not (SiNo ('¿Instalar ' + $App.Nombre + ' de todas formas?') $true)) { return }
    } elseif ($App.Lite -and $memKb -ge 1900000) {
        Info ('Este equipo tiene ' + $memGb + ' GB de RAM: también puede con ' + $Otro.Nombre + ', la versión completa.')
    }

    # 5) Instalar
    Titulo ('Instalar ' + $app.Nombre)
    $apk = Get-Apk $app
    if (-not $apk) {
        Falla ('No encuentro ' + $app.Apk + ' en esta carpeta.')
        Info 'Descárgalo de la página de versiones (Releases) del proyecto y ponlo junto a este instalador.'
        return
    }
    Write-Host ('  Enviando ' + (Split-Path $apk -Leaf) + ' al televisor (puede tardar un minuto)...')
    $r = Run-Adb @('-s', $script:Dev, 'install', '-r', $apk)
    if ($r -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
        Aviso 'El televisor tiene una versión más nueva. Se instala esta encima.'
        $r = Run-Adb @('-s', $script:Dev, 'install', '-r', '-d', $apk)
    }
    if ($r -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        Aviso 'Ya hay una versión firmada con otra clave (por ejemplo, compilada en otra PC).'
        if (SiNo 'Hay que desinstalarla primero (se pierden sus ajustes). ¿Desinstalar y seguir?' $true) {
            Run-Adb @('-s', $script:Dev, 'uninstall', $Pkg) | Out-Null
            $r = Run-Adb @('-s', $script:Dev, 'install', $apk)
        } else { return }
    }
    if ($r -notmatch 'Success') {
        Falla 'No se pudo instalar:'
        Info (($r -split "`n") | Select-Object -Last 3 | Out-String).Trim()
        return
    }
    Ok ($app.Nombre + ' instalado.')

    # 6) Permisos
    Titulo 'Permisos'
    [void](Paso 'Permiso principal (el launcher activa solo sus servicios)' ('pm grant ' + $Pkg + ' android.permission.WRITE_SECURE_SETTINGS'))

    # Botón Inicio (accesibilidad): se AÑADE a la lista sin borrar otros servicios; se quita el del otro launcher.
    $homeSvc = $Pkg + '/' + $Clases + '.HomeWatcherService'
    $acc = @(Lista (Sh 'settings get secure enabled_accessibility_services'))
    $acc = @($acc | Where-Object { -not ($_ -match '^com\.ejemplo\.launcher(lite)?/' -and $_ -match 'HomeWatcherService' -and -not $_.StartsWith($Pkg + '/')) })
    if (-not ($acc | Where-Object { $_.StartsWith($Pkg + '/') -and $_ -match 'HomeWatcherService' })) { $acc += $homeSvc }
    Sh ('settings put secure enabled_accessibility_services ' + ($acc -join ':')) | Out-Null
    Sh 'settings put secure accessibility_enabled 1' | Out-Null
    if ((Sh 'settings get secure enabled_accessibility_services') -match [regex]::Escape($Pkg + '/')) {
        Ok 'Botón Inicio (servicio de accesibilidad)'; $script:Resumen += '[OK] Botón Inicio'
    } else { Aviso 'Botón Inicio: no quedó activo (actívalo en Ajustes > Accesibilidad)'; $script:Resumen += '[!] Botón Inicio' }

    # Lo que estás viendo (lector de notificaciones / reproducción)
    $media = $Pkg + '/' + $Clases + '.MediaWatcherService'
    Sh ('cmd notification allow_listener ' + $media) | Out-Null
    $nl = Sh 'settings get secure enabled_notification_listeners'
    if ($nl -notmatch [regex]::Escape($Pkg + '/')) {
        $l = @(Lista $nl)
        $l += $media
        Sh ('settings put secure enabled_notification_listeners ' + ($l -join ':')) | Out-Null
    }
    if ((Sh 'settings get secure enabled_notification_listeners') -match [regex]::Escape($Pkg + '/')) {
        Ok 'Lo que estás viendo y la última canción'; $script:Resumen += '[OK] Lo que estás viendo'
    } else { Aviso 'Lo que estás viendo: no quedó activo'; $script:Resumen += '[!] Lo que estás viendo' }

    [void](Paso 'Uso de apps (ordenar por la última usada)' ('appops set ' + $Pkg + ' GET_USAGE_STATS allow'))
    [void](Paso 'Mostrar sobre otras apps (abrirse al encender)' ('appops set ' + $Pkg + ' SYSTEM_ALERT_WINDOW allow'))
    [void](Paso 'Ubicación aproximada (clima y hora)' ('pm grant ' + $Pkg + ' android.permission.ACCESS_COARSE_LOCATION'))
    [void](Paso 'Ubicación precisa (clima y hora)' ('pm grant ' + $Pkg + ' android.permission.ACCESS_FINE_LOCATION'))
    if ($sdk -ge 26) {
        $r = Sh ('pm grant ' + $Pkg + ' com.android.providers.tv.permission.READ_EPG_DATA')
        if ($r -match 'Exception|rror|Unknown|not a changeable') { Info 'Miniaturas del televisor: este equipo no tiene la lista «Seguir viendo» del sistema (normal en Fire TV).' }
        else { Ok 'Miniaturas del televisor (lista «Seguir viendo»)'; $script:Resumen += '[OK] Miniaturas del televisor' }
    }
    $r = Sh ('dumpsys deviceidle whitelist +' + $Pkg)
    if ($r -match 'Added|already') { Ok 'Que el sistema no cierre el launcher' } else { Info 'Ahorro de energía: este equipo no permite la excepción (no es necesario).' }

    if (-not $amazon -and $sdk -ge 29) {
        if (SiNo '¿Usarlo como launcher predeterminado (el botón Inicio lo abre directamente)?' $true) {
            [void](Paso 'Launcher predeterminado' ('cmd role add-role-holder android.app.role.HOME ' + $Pkg))
        }
    } elseif ($amazon) {
        Info 'Fire TV no permite cambiar el inicio de Amazon: el botón Inicio abre este launcher con el servicio de accesibilidad.'
    }
    $instOtro = Sh ('pm list packages ' + $otro.Pkg)
    if ($instOtro -match ('(?m)^package:' + [regex]::Escape($otro.Pkg) + '\r?$')) {
        Info ($otro.Nombre + ' también está instalado: el botón Inicio ahora abre ' + $app.Nombre + '.')
    }

    # 7) Abrir
    Titulo 'Listo'
    Sh ('am start -n ' + $Pkg + '/' + $Clases + '.MainActivity') | Out-Null
    Ok ($app.Nombre + ' está abierto en el televisor.')
    Write-Host ''
    Write-Host 'Resumen:'
    foreach ($x in $script:Resumen) { Write-Host ('  ' + $x) }
    Write-Host ''
    Info 'Si algo quedó con [!], el launcher también lo puede pedir en Ajustes > Permisos.'
    Run-Adb @('disconnect', $script:Dev) | Out-Null
}
finally {
    Write-Host ''
    Read-Host 'Presiona Enter para cerrar' | Out-Null
}
