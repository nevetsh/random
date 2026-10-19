# Sanitize the config resources that MeowMod bundles into its jar.
#
# The jar carries a copy of the configuration folder so that other players can
# pick one and copy it into their own config folder. What goes in there is
# therefore written to strangers: only settings may be shared, never the person
# behind them.
#
# The rule is a whitelist. Everything that is not named below is deleted, and
# what stays has its personal values blanked out. The whitelist is deliberately
# explicit: a new file in the config folder is not shared until it has been
# looked at and put on the list.
#
#   Run from the project root:  powershell -File tools/sanitize-configs.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$bundle = Join-Path $root 'src\main\resources\configs'

if (-not (Test-Path -LiteralPath $bundle)) {
	throw "No config bundle at $bundle"
}

# ---------------------------------------------------------------- whitelist ---

# Settings files, one per mod.
$keepFiles = @(
	'badoptimizations.txt', 'bazaarutils.json', 'BBEConfig.json', 'c2me.toml',
	'cariboustonks.json', 'chatting.json', 'dynamic_fps.json', 'entityculling.json',
	'fadeless.json', 'ferritecore.mixin.properties', 'hud-registry.json',
	'immediatelyfast.json', 'lithium.properties', 'midnightlib.json',
	'minecraft-keybinds.json', 'modernfix-mixins.properties', 'modmenu.json',
	'moreculling.toml', 'MouseTweaks.cfg', 'noisium.json', 'notenoughcrashes.json',
	'oneconfig.json', 'packetfixer.properties', 'particle_core_config.toml',
	'particle_core_disabled_optimizations_v2.json', 'rrp.properties',
	'scalablelux.properties', 'scrollable_tooltips.toml', 'skyblocker.json',
	'skyhud.json', 'skyphage.json5', 'sodium-extra-options.json',
	'sodium-extra.properties', 'sodium-fingerprint.json', 'sodium-mixins.properties',
	'sodium-options.json', 'storagelens.json', 'tangos-hud-lib.json', 'themes.json',
	'transition.json', 'trender.json', 'vmp.properties', 'yacl.json5',
	'zfastnoise.mixin.properties', 'zfastnoise.mixin.toml'
)

# Folders whose every file is a setting.
$keepDirs = @(
	'bazaarutils', 'cariboustonks', 'catharsis', 'crash_assistant', 'fabric',
	'fzzy_config', 'huds', 'iconographic', 'meowdding-lib', 'NoChatReports',
	'odinClient', 'simplechat', 'skyblockpv', 'snowbird'
)

# Folders that hold settings next to player data: only the named entries stay.
$keepPartial = [ordered]@{
	'NoammAddons' = @(
		'commandShortcuts.json', 'config.json', 'currentConfig.json',
		'customHiders.json', 'dungeonWaypoints.json', 'noammaddons_sounds.json',
		'party_command_blacklist.json'
	)
	'odin' = @('odin-config.json')
	'scamscreener_marketguard' = @('config.json')
	'skyblocker' = @(
		'chat_rules.json', 'custom_secret_waypoints.json', 'greenhouse_presets.json',
		'hud_widgets.json', 'secret_waypoint_color.json', 'shortcuts.json',
		'speed_presets.json', 'status_bars.json', 'warp_autocomplete.json',
		'waypoints.json'
	)
}

# ------------------------------------------------------------------ pruning ---

$before = Get-ChildItem -LiteralPath $bundle -Recurse -File
$beforeBytes = ($before | Measure-Object -Property Length -Sum).Sum
$removed = New-Object System.Collections.Generic.List[string]

Get-ChildItem -LiteralPath $bundle -Force | ForEach-Object {
	$name = $_.Name
	if ($_.PSIsContainer) {
		if ($keepDirs -contains $name) { return }
		if ($keepPartial.Contains($name)) {
			$allowed = $keepPartial[$name]
			Get-ChildItem -LiteralPath $_.FullName -Force | ForEach-Object {
				if ($allowed -notcontains $_.Name) {
					$removed.Add("$name/$($_.Name)")
					Remove-Item -LiteralPath $_.FullName -Recurse -Force
				}
			}
			return
		}
		$removed.Add("$name/")
		Remove-Item -LiteralPath $_.FullName -Recurse -Force
	} else {
		if ($keepFiles -notcontains $name) {
			$removed.Add($name)
			Remove-Item -LiteralPath $_.FullName -Force
		}
	}
}

# ---------------------------------------------------------------- blanking ---

# The player's own identity: the account UUIDs the instance keeps in its
# player-data folders, and the profile name that goes with them.
$playerUuids = @(
	'1fae8149-cd91-49b0-bc08-aded73e9c227', '236535ea-c7e2-4632-ac75-1f8ad2e52762',
	'409db3ff-39e4-48e3-80ae-41bcba7c35d0', '420a4f1e-a054-4661-88ec-a59a74258966',
	'4d442c88-3617-45b2-99fc-e441c3dad290', 'cf654c06-b360-4b34-9540-9db0fdbc2196',
	'f930d102-3bee-4836-813f-4461a662b002',
	'055dc3c3-39ee-44a3-b189-c853ec6f3e11', '099caff4-90b8-4938-956a-bf5fe0ec9b7d',
	'0add5c60-8e3f-4f0a-9057-5f99ca12b3ad', '1a4592cc-9457-45a4-8f90-9d7c73aeaa1a',
	'3d1010a0-3db4-4ce5-b5ae-5468cb61e442', '55912c00-8bbb-4fa5-9f1a-5588ec5b251d',
	'e8bee4de-f2ba-49e8-a2e2-69775f99c7bf'
)
$playerNames = @('Raspberry', 'Steven')

# Keys whose value is a secret or an identity. The whole key has to match, so
# that a setting like "Secret Color" is left alone. An empty value is left
# alone as well: there is nothing to hide in it.
$secretKeys = @(
	'token', 'accesstoken', 'access_token', 'refreshtoken', 'refresh_token',
	'idtoken', 'id_token', 'secret', 'clientsecret', 'client_secret', 'password',
	'passwd', 'apikey', 'api_key', 'apisecret', 'api_secret', 'webhook',
	'webhookurl', 'webhook_url', 'session', 'sessionid', 'session_id', 'uuid',
	'playeruuid', 'player_uuid', 'username', 'user_name', 'nickname', 'customname',
	'custom_name', 'channelid', 'channel_id', 'discordid', 'discord_id',
	'encryptionkey', 'encryption_key', 'encryptionpassphrase', 'encryption_passphrase'
)

$keyPattern = ($secretKeys | ForEach-Object { [regex]::Escape($_) }) -join '|'

$jsonSecret = [regex]::new('("(?i:' + $keyPattern + ')"\s*:\s*")(?!\s*")[^"]*(")', 'IgnoreCase')
$lineSecret = [regex]::new('^([ \t]*[\w.-]*(?i:' + $keyPattern + ')[\w.-]*[ \t]*[=:][ \t]*)\S.*$', 'IgnoreCase, Multiline')
$webhook = [regex]::new('https?://(?:[\w.-]+\.)?discord(?:app)?\.com/api/webhooks/[\w/?=&%-]+')
$userPath = [regex]::new('([A-Za-z]:\\+Users\\+|/(?:home|Users)/)[^\\/\s"'']+')
$uuidAny = [regex]::new('\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b')
$privateIp = [regex]::new('\b(?:192\.168|10)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b')

$textExtensions = @('json', 'json5', 'jsonc', 'toml', 'cfg', 'conf', 'properties', 'ini', 'txt', 'yml', 'yaml', 'js', 'md', 'jexl')

$blanked = New-Object System.Collections.Generic.List[string]
$changed = 0
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

Get-ChildItem -LiteralPath $bundle -Recurse -File | ForEach-Object {
	$ext = $_.Extension.TrimStart('.').ToLowerInvariant()
	if ($textExtensions -notcontains $ext) { return }

	$text = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
	if ($null -eq $text) { return }
	$original = $text

	# The player's own account first: those UUIDs are known, so they go.
	foreach ($u in $playerUuids) {
		$text = $text.Replace($u, '00000000-0000-0000-0000-000000000000')
		$text = $text.Replace($u.Replace('-', ''), '00000000000000000000000000000000')
	}
	foreach ($n in $playerNames) {
		$text = $text -replace [regex]::Escape($n), '<blanked>'
	}

	# Any other UUID is treated as an identity as well, so an account this
	# list does not know about cannot slip through.
	$text = $uuidAny.Replace($text, '00000000-0000-0000-0000-000000000000')

	$text = $text.Replace($webhook, '<blanked webhook>')
	$text = $jsonSecret.Replace($text, '$1<blanked>$2')
	$text = $lineSecret.Replace($text, '$1<blanked>')
	$text = $text.Replace($userPath, '$1<blanked>')
	$text = $privateIp.Replace($text, '<blanked>')

	if ($text -ne $original) {
		[System.IO.File]::WriteAllText($_.FullName, $text, $utf8NoBom)
		$changed++
		$blanked.Add($_.FullName.Substring($bundle.Length + 1))
	}
}

# ----------------------------------------------------------------- report ----

$after = Get-ChildItem -LiteralPath $bundle -Recurse -File
$afterBytes = ($after | Measure-Object -Property Length -Sum).Sum

"Removed {0} entries" -f $removed.Count
$removed | Sort-Object | ForEach-Object { "  - $_" }
""
"Kept {0} files, {1:N0} bytes (was {2} files, {3:N0} bytes)" -f $after.Count, $afterBytes, $before.Count, $beforeBytes
""
"Blanked personal values in {0} files:" -f $changed
$blanked | Sort-Object | ForEach-Object { "  ~ $_" }
""
"Remaining top-level entries ({0}):" -f (Get-ChildItem -LiteralPath $bundle).Count
Get-ChildItem -LiteralPath $bundle | Sort-Object { $_.PSIsContainer }, Name | ForEach-Object {
	if ($_.PSIsContainer) {
		$f = Get-ChildItem -LiteralPath $_.FullName -Recurse -File
		"  DIR  {0,-28} files={1,-4} {2,10:N0} bytes" -f $_.Name, $f.Count, (($f | Measure-Object Length -Sum).Sum)
	} else {
		"  FILE {0,-28} {1,10:N0} bytes" -f $_.Name, $_.Length
	}
}
