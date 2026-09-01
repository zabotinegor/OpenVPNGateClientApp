# -Shell is set by protect-agent-git-command.sh, the bash-matcher entrypoint, so
# the guard knows which shell will actually run the command line it is judging.
# It is a hint only: the PreToolUse payload's own tool_name is consulted too, and
# anything that is not a positive bash signal is treated as PowerShell - the
# stricter of the two grammars (see $bashSyntax below).
param([string]$Shell = '')

$ErrorActionPreference = 'Stop'

$protected = @('main', 'dev', 'master', 'develop')
$protectedPattern = '(?:main|dev|master|develop)'
# Value flags (-C, --git-dir, --work-tree, -c, --exec-path, --namespace) take a
# token that may be a quoted, space-containing path. \S+ stops at the first
# embedded space even inside quotes, which truncates the match and stops the
# whole $gitPrefixPattern from consuming the flag - so the mutation matchers
# below never reach the subcommand (commit/push/reset/branch) that follows a
# quoted path with a space, and no matcher sets $reason: a protected-branch
# mutation silently gets '{}' (allow). Match the token the same way
# Get-GitTargetPath's tokenizer does (below) so a quoted value with a space
# does not break mutation detection.
#
# The token allows a run of concatenated quoted/unquoted/escaped fragments -
# bash glues 'a'"b" or 'a'\''b' into ONE word with no separator between the
# pieces - wrapped in an ATOMIC group (?>...) rather than left as a bare
# trailing '+'. A bare trailing '+' here let a long bare run split between the
# outer '+' and the inner '+' of '[^\s"']+' in exponentially many ways; with
# no upstream anchor forcing an early success, every mutation matcher below
# that legitimately fails to match (e.g. the commit matcher against a push
# command) forced the engine through that whole exponential space before it
# could conclude failure - a multi-minute hang (ReDoS) on any ordinary -C path
# once it was a few dozen characters long. An atomic group commits to the
# single greedy parse it finds and never backtracks INTO it to try an
# alternate split, which is what actually removes the exponential blowup -
# not dropping the repetition. Dropping the repetition instead (a single,
# non-repeating alternative) fixed the ReDoS but reopened exactly the
# concatenated-quote and escaped-quote gaps this atomic form closes:
# 'git -C '\''foo'\''"bar" commit' resolved its true target fine in
# Get-GitTargetPath (whose own tokenizer already used this atomic-style
# repeated form), but the separate, weaker mutation-matcher pattern below
# stopped at the first quoted fragment, could not match through to '\s+commit',
# and silently allowed the commit. '\\.' as its own alternative (outside a
# quoted run) covers both an escaped character in general and bash's
# single-quote-escape trick ('\'' inserts a literal quote between two
# single-quoted runs); '"[^"\\]*"'-with-escape covers a backslash-escaped
# quote surviving inside a double-quoted run the way ConvertTo-NormalizedCommand
# already preserves it.
$gitValuePattern = '(?>(?:"(?:\\.|[^"\\])*"|''[^'']*''|\\.|[^\s"''\\]+)+)'
$gitPrefixPattern = "\bgit(?:\s+-C\s+$gitValuePattern|\s+--git-dir(?:=$gitValuePattern|\s+$gitValuePattern)|\s+--work-tree(?:=$gitValuePattern|\s+$gitValuePattern)|\s+--no-pager|\s+--paginate|\s+--bare|\s+-c\s+$gitValuePattern|\s+--exec-path(?:=$gitValuePattern|\s+$gitValuePattern)|\s+--namespace=$gitValuePattern|\s+--no-replace-objects|\s+--no-optional-locks|\s+--literal-pathspecs|\s+--no-literal-pathspecs|\s+--glob-pathspecs|\s+--noglob-pathspecs|\s+--icase-pathspecs)*"
$payloadText = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($payloadText)) {
    Write-Output '{}'
    exit 0
}

$payload = $null
try {
    $payload = $payloadText | ConvertFrom-Json
}
catch {
    Write-Output '{}'
    exit 0
}
if ($null -eq $payload) {
    Write-Output '{}'
    exit 0
}
$toolInput = if ($null -ne $payload.tool_input) { $payload.tool_input } else { $payload.toolArgs }
if ($toolInput -is [string]) {
    $rawString = $toolInput
    try { $toolInput = $toolInput | ConvertFrom-Json }
    catch { $toolInput = $rawString }
}
$command = if ($toolInput -is [string]) { $toolInput } elseif ($null -ne $toolInput -and $null -ne $toolInput.command) { [string]$toolInput.command } else { '' }
$cwd = if (-not [string]::IsNullOrWhiteSpace([string]$payload.cwd)) { [string]$payload.cwd } else { (Get-Location).Path }

# Which shell will actually execute this command line decides its grammar, and
# the two grammars disagree on the one character that decides where a command
# ends. In bash '\;' is a literal semicolon (one command); in PowerShell '\' is
# an ordinary argument character and ';' still ends the statement (two commands).
# Applying bash's escaping to a PowerShell command line merged
# 'git -C <feature> status \; git commit' into a single segment, so the guard
# judged the whole line against the feature repo named by -C and let the second
# command - a real commit into the session repo, which may sit on main - through
# unevaluated.
#
# The hook is registered for BOTH matchers (.github/hooks/protected-branches.json:
# "matcher": "bash|powershell"), so neither grammar can simply be assumed. Take
# the shell from a positive signal only - the -Shell hint passed by the bash
# entrypoint, or the payload's own tool_name - and default to PowerShell
# otherwise, because PowerShell's rules cut MORE separators and therefore
# evaluate more segments: an unknown shell fails toward more checking, not less.
$bashSyntax = $Shell -match '(?i)^(?:bash|sh)$'
if (-not $bashSyntax) { $bashSyntax = ([string]$payload.tool_name) -match '(?i)bash' }

# Whitespace is collapsed so the mutation matchers below can rely on single
# spaces - but ONLY outside quotes. A blanket '\s+' -> ' ' rewrote the actual
# argument of 'git -C "/srv/main  repo" commit' to '/srv/main repo'; that path
# does not exist, resolution failed, and the guard fell back to judging the
# command against the caller's own (feature-branch) checkout instead of the
# repository on main that the command really mutates.
#
# A whitespace run that contains a NEWLINE is not collapsed to a space either.
# A newline terminates a command in both shells, so 'git -C <feature> status'
# and a following 'git commit' on the next line are two commands; flattening the
# newline to a space merged them into one segment and produced the same
# skip-the-second-command bypass. Emit an explicit ';' so segmentation cuts
# there. Line continuations ('\' in bash, '`' in PowerShell) are the deliberate
# exception - they join two physical lines into ONE command, so they collapse to
# a plain space; turning one into a cut would split 'git `<newline> commit' and
# leave the commit in a segment that no longer starts with 'git' - unevaluated.
#
# A bash heredoc ('<<EOF' / '<<-EOF' / '<<~EOF', delimiter optionally quoted)
# is the one construct where that same newline-terminates-a-command rule is
# WRONG: everything between the operator and a line matching the delimiter is
# stdin for the single command the heredoc is attached to, not a run of new
# commands. Left unrecognized, the newline rule above fires on every line
# inside the body too - severing 'git -C <target>' on the heredoc's own
# opening line from the 'commit'/'push' that follows it whenever a body line
# lands between them in the physical text ('git -C <target> commit -F -
# <<EOF' ... body ... 'EOF'), or turning body text that happens to start with
# a word like a git command's own into a spurious extra segment. Either way
# the guard evaluates a fragment that never matches $gitPrefixPattern in the
# form the real command took, judges the wrong (or no) repository, and
# silently allows a mutation that really lands on the heredoc's target.
# Replace each newline strictly inside a heredoc body with NUL - a character
# no real command line contains and not whitespace, so neither this function's
# own newline rule nor Split-CommandSegments' separator scan reacts to it -
# so the whole heredoc (opening line, body, terminator line) stays one
# unbroken run. The newline that ends the TERMINATOR line itself is left
# alone, so whatever genuinely follows the heredoc on the next real line still
# starts a new segment as usual. Bash-only: PowerShell's '<' is an ordinary
# redirection operator with no heredoc form, so this is a no-op there.
function Protect-HeredocBodies {
    param([string]$Command, [bool]$BashSyntax)

    if (-not $BashSyntax -or $Command.IndexOf('<<') -lt 0) { return $Command }

    $sb = New-Object System.Text.StringBuilder
    $quote = $null
    $i = 0
    $len = $Command.Length

    while ($i -lt $len) {
        $ch = $Command[$i]

        if ($null -ne $quote) {
            if ($quote -eq '"' -and $ch -eq '\' -and ($i + 1) -lt $len) {
                [void]$sb.Append($ch).Append($Command[$i + 1]); $i += 2; continue
            }
            if ($ch -eq $quote) { $quote = $null }
            [void]$sb.Append($ch); $i++; continue
        }

        if ($ch -eq '\' -and ($i + 1) -lt $len) {
            [void]$sb.Append($ch).Append($Command[$i + 1]); $i += 2; continue
        }

        if ($ch -eq '"' -or $ch -eq "'") { $quote = $ch; [void]$sb.Append($ch); $i++; continue }

        if ($ch -eq '<' -and ($i + 1) -lt $len -and $Command[$i + 1] -eq '<') {
            # A THIRD immediately-following '<' makes this bash's here-string
            # operator ('<<<'), not a heredoc ('<<'). A here-string's argument
            # is a single same-line word/quoted string - there is no multi-line
            # body to protect, and whatever follows (a newline, ';', etc.) starts
            # a normal new command exactly as it would after any other word.
            # Accepting the first two '<' characters and mis-parsing the third
            # '<' plus its argument as a heredoc delimiter left no line in the
            # rest of the command ever matching that bogus "delimiter", so
            # heredoc-body consumption ran to end-of-string and swallowed
            # whatever real command followed on the next line as inert body
            # text. Copy the three characters through unexamined; the normal
            # scan (including quote tracking a few lines below) picks up right
            # after them and handles the rest exactly as it would any other text.
            if (($i + 2) -lt $len -and $Command[$i + 2] -eq '<') {
                [void]$sb.Append($Command[$i]).Append($Command[$i + 1]).Append($Command[$i + 2])
                $i += 3
                continue
            }

            $opStart = $i
            $j = $i + 2
            if ($j -lt $len -and ($Command[$j] -eq '-' -or $Command[$j] -eq '~')) { $j++ }
            while ($j -lt $len -and ($Command[$j] -eq ' ' -or $Command[$j] -eq "`t")) { $j++ }

            # The delimiter may be quoted (quoting only affects body variable
            # expansion in real bash, irrelevant here - only the literal text
            # is needed to recognize the terminator line) or bare.
            $delim = $null
            if ($j -lt $len -and ($Command[$j] -eq '"' -or $Command[$j] -eq "'")) {
                $q = $Command[$j]
                $k = $j + 1
                while ($k -lt $len -and $Command[$k] -ne $q) { $k++ }
                if ($k -lt $len) {
                    $delim = $Command.Substring($j + 1, $k - $j - 1)
                    $j = $k + 1
                }
            } else {
                $k = $j
                while ($k -lt $len -and $Command[$k] -notmatch '[\s;&|]') { $k++ }
                if ($k -gt $j) {
                    $delim = $Command.Substring($j, $k - $j)
                    $j = $k
                }
            }

            if ([string]::IsNullOrEmpty($delim)) {
                # Not a recognizable heredoc operator (bare '<<' with nothing
                # after it) - copy the two characters through unexamined
                # rather than guess at intent.
                [void]$sb.Append($Command[$i]).Append($Command[$i + 1]); $i += 2; continue
            }

            # Copy the operator and delimiter through verbatim, then the rest
            # of THIS physical line unchanged - a real command can still
            # follow the heredoc operator on the same line under bash.
            [void]$sb.Append($Command.Substring($opStart, $j - $opStart))
            $i = $j
            while ($i -lt $len -and $Command[$i] -ne "`n") {
                [void]$sb.Append($Command[$i]); $i++
            }

            # Consume line by line: each line's own LEADING newline becomes a
            # placeholder, until a line's text (leading tabs stripped, as a
            # '<<-' terminator tolerates and a plain '<<' match still accepts
            # since that only closes the body EARLIER - the fail-safe
            # direction, never later) equals the delimiter exactly. That
            # terminator line's own TRAILING newline is left as a real
            # separator. Reaching end-of-string without a matching line - a
            # heredoc a real shell would treat as implicitly closed by input
            # end - places everything remaining inside the body too, which is
            # what real bash does with it.
            #
            # Quote characters and backslashes are also placeholder-neutralized
            # in what gets APPENDED (never in $line, which still holds the raw
            # text the terminator comparison needs). Inside a heredoc body
            # neither character is shell syntax at all - a body is free-form
            # text, most often a commit message, and one stray unmatched
            # apostrophe ("don't") read by ConvertTo-NormalizedCommand's own
            # quote-tracking as an OPENING quote would make it treat every
            # separator in whatever genuinely follows the heredoc - a real
            # '&&', a real newline - as still "inside a string" and never cut
            # there, hiding a later mutating command inside what the guard
            # then treats as one inert blob.
            while ($i -lt $len) {
                [void]$sb.Append([char]0)
                $i++
                $lineStart = $i
                while ($i -lt $len -and $Command[$i] -ne "`n") { $i++ }
                $line = $Command.Substring($lineStart, $i - $lineStart).TrimEnd("`r")
                $lineForCompare = if ($line -match '^\t*(.*)$') { $Matches[1] } else { $line }
                $safeLine = $Command.Substring($lineStart, $i - $lineStart) -replace '[''"\\]', [string][char]0
                [void]$sb.Append($safeLine)
                if ($lineForCompare -eq $delim) { break }
                if ($i -ge $len) { break }
            }
            continue
        }

        [void]$sb.Append($ch); $i++
    }

    return $sb.ToString()
}

function ConvertTo-NormalizedCommand {
    param([string]$Command, [bool]$BashSyntax)

    $sb = New-Object System.Text.StringBuilder
    $quote = $null
    # An opening single-quote immediately preceded by '$' is bash's ANSI-C
    # quoting ($'...'), NOT a plain single-quoted string. Inside $'...' a
    # backslash escapes the next character - including \' for a literal quote
    # that does not end the string - the same way a backslash does inside
    # double quotes. A plain '...' string has no escapes at all: a backslash
    # is ordinary data and the very next unescaped quote closes it. Losing this
    # distinction is what let 'printf '%s' $'x\'; y'; git reset --hard HEAD~1'
    # through - the \' inside the ANSI-C string was read as closing the quote,
    # the guard then saw the ';' as a real separator and 'y'' as a fresh
    # (unterminated) quote that swallowed the rest of the line up to the real
    # closing quote, at which point the actual 'git reset --hard' had already
    # been consumed as string content instead of judged as its own segment.
    $ansiQuote = $false
    $i = 0
    $text = $Command.Trim()

    function Test-IsNewline { param([char]$C) return ($C -eq "`n" -or $C -eq "`r") }

    while ($i -lt $text.Length) {
        $ch = $text[$i]

        if ($null -ne $quote) {
            # Inside quotes every character - whitespace included - is part of the
            # argument and is copied verbatim. In bash a backslash inside DOUBLE
            # quotes still escapes, so an escaped quote must not end the run -
            # and the same is true inside an ANSI-C ($'...') single-quoted run.
            if ($BashSyntax -and ($quote -eq '"' -or $ansiQuote) -and $ch -eq '\' -and ($i + 1) -lt $text.Length) {
                [void]$sb.Append($ch).Append($text[$i + 1]); $i += 2; continue
            }
            # In PowerShell, a backtick inside double quotes escapes the next
            # character (including a closing quote), so the escaped quote must
            # not end the run.
            if (-not $BashSyntax -and $quote -eq '"' -and $ch -eq '`' -and ($i + 1) -lt $text.Length) {
                [void]$sb.Append($ch).Append($text[$i + 1]); $i += 2; continue
            }
            if ($ch -eq $quote) { $quote = $null; $ansiQuote = $false }
            [void]$sb.Append($ch); $i++; continue
        }

        $continuation = $false
        if (($i + 1) -lt $text.Length -and (Test-IsNewline $text[$i + 1])) {
            if ($BashSyntax -and $ch -eq '\') { $continuation = $true }
            elseif (-not $BashSyntax -and $ch -eq '`') { $continuation = $true }
        }
        if ($continuation) {
            $i++
            while ($i -lt $text.Length -and [char]::IsWhiteSpace($text[$i])) { $i++ }
            if ($sb.Length -gt 0 -and $sb.Chars($sb.Length - 1) -ne ' ') { [void]$sb.Append(' ') }
            continue
        }

        # Outside quotes a bash backslash escapes the next character; carry both
        # through untouched so Split-CommandSegments can apply the same rule.
        if ($BashSyntax -and $ch -eq '\' -and ($i + 1) -lt $text.Length) {
            [void]$sb.Append($ch).Append($text[$i + 1]); $i += 2; continue
        }

        if ($ch -eq '"' -or $ch -eq "'") {
            $ansiQuote = ($BashSyntax -and $ch -eq "'" -and $i -gt 0 -and $text[$i - 1] -eq '$')
            $quote = $ch; [void]$sb.Append($ch); $i++; continue
        }

        if ([char]::IsWhiteSpace($ch)) {
            $hasNewline = $false
            while ($i -lt $text.Length -and [char]::IsWhiteSpace($text[$i])) {
                if (Test-IsNewline $text[$i]) { $hasNewline = $true }
                $i++
            }
            [void]$sb.Append($(if ($hasNewline) { ';' } else { ' ' }))
            continue
        }

        [void]$sb.Append($ch); $i++
    }

    return $sb.ToString().Trim()
}

$command = Protect-HeredocBodies -Command $command -BashSyntax $bashSyntax
$normalized = ConvertTo-NormalizedCommand -Command $command -BashSyntax $bashSyntax

# Shell separators only separate commands OUTSIDE quotes. A bare '[;&|]+' split
# truncates a legitimately quoted path that contains one of those characters
# ('git -C "/srv/prod&repo" commit'), so the guard would resolve - and judge - a
# repository other than the one the command actually mutates. Walk the string
# once, tracking quote state, and cut only on separators found outside quotes.
# Each segment carries its offset so callers can keep source order.
#
# A backslash outside quotes is the OTHER way a separator can be literal - but
# only under BASH: 'git -C /srv/prod\&repo commit' passes an unquoted path whose
# '&' bash consumes as an ordinary character. Splitting there truncated the
# segment before 'commit', and the remainder ('repo commit ...') no longer
# started with 'git', so the whole mutation was skipped without ever being
# evaluated - an allow verdict on a commit that really lands on the target repo's
# protected branch. Skip the character after an unquoted backslash so it can
# never be a cut point.
#
# Gated on $BashSyntax because PowerShell has no such rule: there '\' is an
# ordinary character and ';' separates statements regardless of what precedes
# it, so honouring the escape merged two PowerShell commands into one segment
# and left the second one unjudged.
function Split-CommandSegments {
    param([string]$Command, [bool]$BashSyntax)

    $segments = New-Object System.Collections.Generic.List[object]
    $separators = @(';', '&', '|')
    $start = 0
    $quote = $null
    # See ConvertTo-NormalizedCommand's matching $ansiQuote comment: an ANSI-C
    # ($'...') string's internal backslash-escapes (including \' for a literal
    # quote) must not be read as ending the quoted run here either, or the
    # ';'/'&'/'|' inside it get treated as real segment separators and the
    # string's own closing quote gets misread as a fresh opener that then
    # swallows whatever legitimately follows on the line.
    $ansiQuote = $false

    for ($i = 0; $i -lt $Command.Length; $i++) {
        $ch = $Command[$i]

        if ($null -ne $quote) {
            if ($BashSyntax -and ($quote -eq '"' -or $ansiQuote) -and $ch -eq '\' -and ($i + 1) -lt $Command.Length) { $i++; continue }
            # In PowerShell, a backtick inside double quotes escapes the next
            # character (including a closing quote), so the escaped quote must
            # not end the run.
            if (-not $BashSyntax -and $quote -eq '"' -and $ch -eq '`' -and ($i + 1) -lt $Command.Length) { $i++; continue }
            if ($ch -eq $quote) { $quote = $null; $ansiQuote = $false }
            continue
        }

        # Consume the escaped character with the backslash. '\\' therefore eats
        # both backslashes and leaves a following separator live, which is what
        # bash does. Only applied outside quotes, and only for bash command
        # lines: inside single quotes a backslash is literal, and under
        # PowerShell it is not an escape character at all.
        if ($BashSyntax -and $ch -eq '\') { $i++; continue }

        if ($ch -eq '"' -or $ch -eq "'") {
            $ansiQuote = ($BashSyntax -and $ch -eq "'" -and $i -gt 0 -and $Command[$i - 1] -eq '$')
            $quote = $ch; continue
        }

        if ($separators -contains $ch) {
            $segments.Add([pscustomobject]@{ Text = $Command.Substring($start, $i - $start); Index = $start })
            # Consume the whole separator run ('&&', '||', ';;') in one cut.
            while (($i + 1) -lt $Command.Length -and ($separators -contains $Command[$i + 1])) { $i++ }
            $start = $i + 1
        }
    }

    $segments.Add([pscustomobject]@{ Text = $Command.Substring($start); Index = $start })

    # Comma-wrap: a List returned bare is unwrapped by the pipeline, and a
    # single-segment command would then arrive at the caller as a scalar.
    return , $segments
}

# A -C/--git-dir value may be POSIX-absolute ('/d/Apps/CopilotTools', which a
# Git-Bash-issued command produces naturally) or relative. This guard always
# resolves the target through pwsh's own git.exe (see protect-agent-git-command.sh,
# which execs pwsh for both the bash and powershell matchers), so no MSYS path
# translation happens and a POSIX form would silently fail to resolve; and a
# relative target is relative to the command's own cwd, not to this hook
# subprocess's cwd (fixed to the repo root by protected-branches.json's
# "cwd": "."). Normalize both here so resolution is shell- and cwd-independent.
function Resolve-TargetToken {
    param([string]$Value, [string]$BaseDir)

    $clean = ($Value -replace '["'']', '')
    # A shell metacharacter reaches the path only escaped ('/srv/prod\&repo') or
    # quoted; the backslash is the shell's, not part of the directory name. Strip
    # it for exactly those characters so the escaped form resolves to the same
    # repository the quoted form does - otherwise the path stays unresolvable,
    # the caller falls back to the SESSION repo, and a commit into a target repo
    # sitting on main is judged against the session's feature branch and allowed.
    # Restricted to ';', '&' and '|' on purpose: a blanket unescape would maul
    # ordinary Windows paths ('C:\repo' -> 'C:repo'). Restricted to bash command
    # lines for the same reason the segmenter is: ';' and '&' are legal Windows
    # filename characters, so under PowerShell 'C:\a\&b' names a real directory
    # '&b' and un-escaping it would break a path that resolves perfectly well.
    if ($script:bashSyntax) { $clean = ($clean -replace '\\([;&|])', '$1') }
    if ($clean -match '^/([A-Za-z])(/.*)?$') {
        $clean = "$($Matches[1]):$($Matches[2])"
    }
    if (-not [System.IO.Path]::IsPathRooted($clean) -and -not [string]::IsNullOrWhiteSpace($BaseDir)) {
        $clean = [System.IO.Path]::GetFullPath((Join-Path $BaseDir $clean))
    }
    return $clean
}

# A command can act on a repository other than the session's own via
# 'git -C <path>' / 'git --git-dir=<path>'. Judging it by the session cwd is
# wrong in both directions: it reads the wrong branch, and it never applies the
# target repo's '.agenttools-source' exemption - which is what denied a
# CopilotTools push issued from a client-repo session. Resolve the repository a
# SINGLE git segment actually targets (chained segments are resolved and judged
# one by one by the caller, so two different -C targets on one command line can
# never collapse into one branch verdict).
function Get-GitTargetPath {
    param([string]$Segment, [string]$FallbackPath)

    # Only the options between 'git' and its subcommand redirect the repository.
    # A plain search would also hit subcommand flags that reuse the letter -
    # 'git commit -C HEAD~1' reuses a commit message, it does not change repo.
    # Comparisons are case-sensitive on purpose: -c (config) is not -C (path).
    $valueFlags = @('-c', '-C', '--git-dir', '--work-tree', '--namespace', '--exec-path', '--super-prefix', '--config-env')

    # Token pattern keeps a quoted run glued to its token, so both
    # '-C "C:/Copilot Tools"' and '--git-dir="C:/a b/.git"' survive.
    # A backtick-escaped character (PowerShell) is also kept glued so that
    # backtick-escaped quotes do not break the quoted run.
    $tokens = @([regex]::Matches($Segment.Trim(), '(?:[^\s"'']+|"[^"]*"|''[^'']*''|`.)+') | ForEach-Object { $_.Value })
    if ($tokens.Count -eq 0 -or $tokens[0] -ne 'git') { return $FallbackPath }

    $dir = $FallbackPath
    $gitDir = $null

    for ($i = 1; $i -lt $tokens.Count; $i++) {
        $token = $tokens[$i]
        if (-not $token.StartsWith('-')) { break }

        $name = $token
        $inlineValue = $null
        $eq = $token.IndexOf('=')
        if ($eq -gt 0) {
            $name = $token.Substring(0, $eq)
            $inlineValue = $token.Substring($eq + 1)
        }

        if (-not ($valueFlags -ccontains $name)) { continue }

        $value = if ($null -ne $inlineValue) { $inlineValue }
                 elseif ($i + 1 -lt $tokens.Count) { $tokens[++$i] }
                 else { $null }
        if ([string]::IsNullOrWhiteSpace($value)) { continue }

        # git resolves --git-dir/--work-tree paths relative to the directory a
        # preceding -C selected, so -C is applied to $dir first and later path
        # options are resolved against the updated $dir.
        if ($name -ceq '-C') {
            $dir = Resolve-TargetToken -Value $value -BaseDir $dir
        }
        elseif ($name -ceq '--git-dir') {
            $gitDir = Resolve-TargetToken -Value $value -BaseDir $dir
        }
        # --work-tree is deliberately NOT treated as a repository redirect. It
        # overrides only the WORK TREE; without --git-dir the repository is still
        # discovered from the current directory, so
        # 'git --work-tree=<feature-repo> commit' run inside a repo on main still
        # commits to main. Reading the branch at the --work-tree path would report
        # the feature repo's branch and let that protected-branch commit through.
        # Its value is still consumed above so it cannot be mistaken for the
        # subcommand.
    }

    if ($gitDir) { return $gitDir }
    return $dir
}

function Resolve-GitRepoState {
    param([string]$Path)

    $root = ''
    $head = ''
    $gitDir = ''
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $root = ((git -C $Path rev-parse --show-toplevel 2>$null) | Out-String).Trim()
        # --show-toplevel needs a WORK TREE. A path that is itself a git dir
        # ('git --git-dir=<repo>/.git commit') or a bare repo has none, so
        # --show-toplevel fails and leaves Root empty even though the repository -
        # and the branch that commit would land on - resolves perfectly well.
        # An empty Root then tripped the "unresolvable path" fallback below and
        # re-judged the command against the SESSION repo's feature branch, letting
        # a commit onto a protected branch of the --git-dir target through.
        # --absolute-git-dir succeeds in exactly the cases --show-toplevel cannot,
        # so it gives the repository an identity without requiring a work tree.
        $gitDir = ((git -C $Path rev-parse --absolute-git-dir 2>$null) | Out-String).Trim()
        $head = ((git -C $Path branch --show-current 2>$null) | Out-String).Trim().ToLowerInvariant()
    }
    finally {
        $ErrorActionPreference = $previousEap
    }

    $root = ($root -replace '\\', '/').TrimEnd('/')
    $gitDir = ($gitDir -replace '\\', '/').TrimEnd('/')

    if ([string]::IsNullOrWhiteSpace($root) -and -not [string]::IsNullOrWhiteSpace($gitDir)) {
        # '<root>/.git' -> '<root>' so the '.agenttools-source' exemption probe
        # still lands on the work-tree root and so this path shares one canonical
        # key with the same repository addressed via -C. Any other layout (bare
        # repo, linked-worktree git dir) keeps the git dir itself as identity:
        # still a stable key, and the exemption simply does not apply - the
        # fail-safe direction.
        if ($gitDir -match '(?i)^(.+)/\.git$') { $root = $Matches[1] } else { $root = $gitDir }
    }

    return [pscustomobject]@{ Root = $root; Branch = $head; GitDir = $gitDir }
}

$switchPattern = "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+(?:switch|checkout)\s+(?:-\S+\s+)*(?!--)([^\s;&|]+)"

# Repo state is resolved once per distinct target path and reused across the
# segments that share it (a chained command usually hits the same repo twice).
$stateCache = @{}
function Get-CachedRepoState {
    param([string]$Path)
    if (-not $script:stateCache.ContainsKey($Path)) {
        $script:stateCache[$Path] = Resolve-GitRepoState -Path $Path
    }
    return $script:stateCache[$Path]
}

# Effective branch per resolved REPOSITORY. Seeded from the repo's real HEAD on
# first sight and updated by a 'git switch/checkout' segment, so a later segment
# in the SAME repository is judged against the branch the earlier segment moved
# to ('git switch main && git commit' stays denied) while a segment targeting a
# DIFFERENT repository keeps its own branch.
#
# The key is the CANONICAL repository root, not the raw path token: one physical
# repository is reachable through many aliases ('/repo', '/repo/subdir',
# '--git-dir=/repo/.git'). Keying by the raw token gave each alias its own entry,
# so 'git -C /repo switch main && git -C /repo/subdir commit' seeded the second
# segment from the pre-command HEAD (still the feature branch) and allowed a
# commit that the shell actually lands on main.
$branchByPath = @{}

$reason = $null
foreach ($segment in (Split-CommandSegments -Command $normalized -BashSyntax $bashSyntax)) {
    if ($reason) { break }

    $text = $segment.Text.Trim()
    if ($text -notmatch '(?i)^git(\s|$)') { continue }

    # Resolve and judge THIS segment against its own repository. Collapsing a
    # multi-target command line into one verdict let the wrong branch decide:
    # 'git -C <feature> status && git -C <main> commit' was judged entirely on
    # the feature repo and the protected-branch commit went through.
    $evalPath = Get-GitTargetPath -Segment $text -FallbackPath $cwd
    $state = Get-CachedRepoState -Path $evalPath

    # An unresolvable -C path must not become an escape hatch. Fall back to the
    # session repo and keep evaluating rather than sailing through with no branch.
    if ([string]::IsNullOrWhiteSpace($state.Root) -and $evalPath -ne $cwd) {
        $evalPath = $cwd
        $state = Get-CachedRepoState -Path $evalPath
    }

    # Canonical identity for the branch-state table. The ABSOLUTE GIT DIR is the
    # identity, not the work-tree root: git reports the same --absolute-git-dir
    # for every alias of one checkout ('/repo', '/repo/subdir',
    # '--git-dir=/repo/.git'), and - unlike the root - it is also the same for
    # the two ways a LINKED WORKTREE can be addressed. '-C /wt' resolves its root
    # to '/wt', while '--git-dir=/repo/.git/worktrees/wt --work-tree=/wt' has no
    # work tree to report and previously fell back to keying by the
    # administrative git dir, so the two forms seeded two independent branch
    # entries: 'git --git-dir=... switch main && git -C /wt commit' kept the
    # second segment on the pre-switch feature branch and allowed a commit the
    # shell really lands on main. Both forms report the same absolute git dir, so
    # keying by it collapses them onto one entry. Root, then the raw path, remain
    # as fallbacks for when nothing resolved (Root is still what the
    # '.agenttools-source' exemption probes, since that marker lives in the
    # work tree).
    $stateKey = if (-not [string]::IsNullOrWhiteSpace($state.GitDir)) { $state.GitDir }
                elseif (-not [string]::IsNullOrWhiteSpace($state.Root)) { $state.Root }
                else { $evalPath }

    if (-not $branchByPath.ContainsKey($stateKey)) {
        $branchByPath[$stateKey] = $state.Branch
    }
    $eff = $branchByPath[$stateKey]

    # Switch/checkout moves the effective branch for every LATER segment that
    # targets the same repository. Applied before the exemption check so a mixed
    # command line stays consistent.
    $sm = [regex]::Match($text, $switchPattern)
    if ($sm.Success) {
        $t = ($sm.Groups[1].Value -replace "^['`"]+|['`"]+$").ToLowerInvariant()
        if (-not $t.StartsWith('-')) { $branchByPath[$stateKey] = $t }
    }

    $repoRoot = $state.Root
    # The exemption is per target repository, not per command line: a segment
    # acting on a CopilotTools source checkout is exempt, while a sibling segment
    # in the same command line that targets a client repo is still checked.
    if ((-not [string]::IsNullOrWhiteSpace($repoRoot)) -and
            (Test-Path -LiteralPath (Join-Path $repoRoot '.agenttools-source'))) {
        continue
    }

    if ($text -match "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+commit\b") {
        if ($protected -contains $eff) {
            $reason = "Direct commit on protected branch '$eff' is forbidden."
        }
    }

    if (-not $reason -and $text -match "(?i)$gitPrefixPattern\s+push\b") {
        $pushMatch = [regex]::Match($text, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+push\b")
        # Scope the force-flag scan to this push's own argument slice, not the
        # whole command line - otherwise an earlier segment's unrelated flag
        # (most notably `git commit -F file.txt`, read-message-from-file) is
        # matched by the case-insensitive -f alternative and produces a false
        # 'force-push forbidden' block on a command that pushes nothing by
        # force at all (e.g. `git commit -q -F msg.txt && git push -q origin
        # br`). Segment splitting already did the coarse scoping; the tail of
        # this segment is this push's own argument list.
        #
        # The long '--force...' forms stay case-insensitive (git recognizes
        # only the canonical lowercase spelling, so there is no colliding flag
        # to protect against, and matching a stray '--FORCE' typo is still the
        # safe/desired outcome). The short '-f' form is matched case-sensitively
        # on its own so it cannot match '-F', which is a different, unrelated
        # git flag on several subcommands (e.g. commit's read-message-from-file).
        $pushArgSeg = if ($pushMatch.Success) { $text.Substring($pushMatch.Index + $pushMatch.Length) } else { '' }
        if (($pushArgSeg -match '(?i)(?:^|\s)--force(?:-with-lease(?:=\S*)?|-if-includes)?(?:\s|$|[;&|])') -or
                ($pushArgSeg -cmatch '(?:^|\s)-f(?:\s|$|[;&|])')) {
            $reason = 'Force-push is forbidden in client repositories.'
        }
        elseif ($text -match '(?i)(?:^|\s)(?:--all|--branches|--mirror)(?:\s|$|[;&|])') {
            $reason = 'Bulk push (--all/--branches/--mirror) may update protected refs and is forbidden.'
        }
        else {
            # Release-flow archive exception: permit the two dev-branch mutations that occur in
            # the archive step after a release squash merge (step 5 of release-flow-orchestrator).
            #   1. git push origin --delete dev   (remove old dev after pushing the archive branch)
            #   2. git push [-u] origin dev        (push new dev recreated from merged main)
            # Authorization condition for (1): origin/archive/archive-dev-* exists (archive was pushed).
            # Authorization condition for (2): same + local dev SHA == origin/main SHA.
            $allowReleaseArchivePush = $false
            # Use only explicit remote-target patterns — NOT $eff -eq 'dev'. When the caller
            # is checked out on dev, $eff -eq 'dev' would set $isDevPush for ANY push command
            # (e.g. `git push origin HEAD:main`), letting archive+SHA grant $allowReleaseArchivePush
            # and skip all protected-branch checks.
            $isDevPush   = $text -match "(?i)\b(?:origin|upstream)\s+(?:-u\s+)?dev(?![-\w/.])"
            $isDevDelete = $text -match "(?i)(?:^|\s)(?:--delete|-d)\s+dev(?![-\w/.])"
            # Guard: if the same command mentions any other protected branch name anywhere
            # (bare token, +token, :token, or refs/heads/ form), the exception does not
            # apply — e.g. `git push origin dev main` or `git push origin --delete dev main`
            # pass extra refspecs positionally, so position-anchored patterns are not enough.
            # False positives only deny the narrow exception (fail-safe: push stays blocked).
            if (($isDevPush -or $isDevDelete) -and
                $text -match '(?i)(?:^|[\s+:])(?:refs/heads/)?(?:main|master|develop)(?![-\w/.])') {
                $isDevPush = $false; $isDevDelete = $false
            }
            if ($isDevPush -or $isDevDelete) {
                $previousEap2 = $ErrorActionPreference
                try {
                    $ErrorActionPreference = 'Continue'
                    $remotes = (git -C $evalPath branch -r 2>$null) -join "`n"
                    if ($remotes -match 'archive/archive-dev-') {
                        if ($isDevDelete) {
                            $allowReleaseArchivePush = $true
                        } elseif ($isDevPush) {
                            $devSha  = ((git -C $evalPath rev-parse dev        2>$null) | Out-String).Trim()
                            $mainSha = ((git -C $evalPath rev-parse origin/main 2>$null) | Out-String).Trim()
                            if ($devSha -and $mainSha -and $devSha -eq $mainSha) { $allowReleaseArchivePush = $true }
                        }
                    }
                }
                finally { $ErrorActionPreference = $previousEap2 }
            }
            if (-not $allowReleaseArchivePush) {
                if ($protected -contains $eff -and $text -notmatch '(?i)\bHEAD:') {
                    $reason = "Direct push from protected branch '$eff' is forbidden."
                }
                elseif (
                    $text -match "(?i)\b(?:origin|upstream)\s+$protectedPattern(?![-\w/.])" -or
                    $text -match "(?i)\b(?:origin|upstream)\s+\+$protectedPattern(?![-\w/.])" -or
                    $text -match "(?i)\b[a-zA-Z0-9_/\-]+:(?:refs/heads/)?$protectedPattern(?![-\w/.])" -or
                    $text -match "(?i)(?:^|\s)\+(?:refs/heads/)?$protectedPattern(?![-\w/.])" -or
                    $text -match "(?i)\brefs/heads/$protectedPattern(?![-\w/.])" -or
                    $text -match "(?i)(?:^|\s)(?:--delete|-d)\s+$protectedPattern(?![-\w/.])" -or
                    $text -match "(?i)(?:^|\s):$protectedPattern(?![-\w/.])"
                ) {
                    $reason = 'Direct push, deletion, or recreation of a protected branch is forbidden.'
                }
            }
        }
    }

    if (-not $reason -and $text -match "(?i)$gitPrefixPattern\s+update-ref\b[^\r\n]*refs/heads/$protectedPattern(?![-\w/.])") {
        $reason = 'Direct protected branch ref mutation is forbidden.'
    }

    # Reset: judged against this segment's own effective branch. Strip quotes from
    # switch targets so git switch "main" does not bypass the protected check.
    if (-not $reason -and $text -match "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+reset\b") {
        $rm = [regex]::Match($text, "(?i)(?:^|[;&|]\s*)$gitPrefixPattern\s+reset\b")
        $argSeg = $text.Substring($rm.Index + $rm.Length)
        if ($protected -contains $eff -and
                $argSeg -match '(?i)(?:^|\s)(?:--hard|--keep|--merge)(?:\s|$)') {
            $reason = "Hard reset on protected branch '$eff' is forbidden."
        }
    }

    # Branch -f: the forced pointer target is a ref name, so this check does not
    # depend on the segment's checked-out branch.
    if (-not $reason -and $text -match "(?i)$gitPrefixPattern\s+branch\b") {
        foreach ($bm in [regex]::Matches($text, "(?i)$gitPrefixPattern\s+branch\b")) {
            $seg = $text.Substring($bm.Index)
            if ($seg -match '(?i)(?:^|\s)(?:-f|--force)(?:\s|$|[;&|])') {
                if ($seg -match "(?i)$gitPrefixPattern\s+branch\b((?:\s+-\S+)*)\s+([^\s-]\S*)") {
                    $branchTarget = ($Matches[2] -replace "^['`"]+|['`"]+$").ToLowerInvariant()
                    if ($protected -contains $branchTarget) {
                        $reason = 'Forcing a protected branch pointer is forbidden.'
                        break
                    }
                }
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($reason)) {
    Write-Output '{}'
    exit 0
}

[ordered]@{
    hookSpecificOutput = [ordered]@{
        hookEventName = 'PreToolUse'
        permissionDecision = 'deny'
        permissionDecisionReason = $reason
    }
} | ConvertTo-Json -Depth 5 -Compress
