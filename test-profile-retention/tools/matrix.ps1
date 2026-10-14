# Runs the reproducer for N = 1, 3, 6, 12 distinct profiles in each variant, and the shared-profile control,
# and writes one row per run to results/matrix.csv: the live heap after a full GC and the pinning threads and hooks,
# after the first test class and after the last.
#
#   pwsh tools/matrix.ps1 -Gradle <path to gradlew(.bat)>
param([Parameter(Mandatory)] [string] $Gradle)

$root = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force "$root/results" | Out-Null
$out = "$root/results/matrix.csv"
"variant,classes,profiles,first_heap_kb,last_heap_kb,last_loaders,ryuk_threads,jgit_threads,shutdown_hooks,seconds" | Set-Content $out

$variants = [ordered]@{
    'baseline'          = @()
    'devservices'       = @('-Pdevservices=true')
    'info'              = @('-Pinfo=true')
    'devservices+info'  = @('-Pdevservices=true', '-Pinfo=true')
}

function Run([string] $name, [string[]] $props, [string[]] $classes, [int] $profiles) {
    $tests = $classes | ForEach-Object { '--tests'; $_ }
    $started = Get-Date
    & $Gradle -p $root test @tests @props --console=plain -q 2>&1 | Out-Null
    $seconds = [int]((Get-Date) - $started).TotalSeconds
    $rows = Import-Csv "$root/build/retention.csv"
    $first = $rows[0]; $last = $rows[-1]
    "$name,$($rows.Count),$profiles,$($first.heap_used_kb),$($last.heap_used_kb),$($last.quarkus_loaders),$($last.ryuk_threads),$($last.jgit_threads),$($last.shutdown_hooks),$seconds" |
        Add-Content $out
    Copy-Item "$root/build/retention-details.txt" "$root/results/details-$name-$profiles.txt" -Force
    Write-Host "$name N=$profiles done in $seconds s"
}

foreach ($v in $variants.GetEnumerator()) {
    foreach ($n in 1, 3, 6, 12) {
        $classes = 1..$n | ForEach-Object { 'repro.distinct.P{0:D2}Test' -f $_ }
        Run $v.Key $v.Value $classes $n
    }
    Run "$($v.Key) shared-profile control" $v.Value @('repro.shared.*') 1
}
Get-Content $out
