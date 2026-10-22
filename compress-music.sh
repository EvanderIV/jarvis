#!/usr/bin/env bash
# compress-music.sh — index the Jarvis music library and re-encode heavy files to MP3.
#
# LMS has to transcode FLAC (and other non-MP3 formats) on the fly every time a track
# plays; MP3s at a sane bitrate are streamed as-is. This script:
#   1. Indexes every audio file (codec, bitrate, sample rate, duration, size) into a TSV.
#   2. Re-encodes to a 128 kbps MP3:
#        - every non-MP3 file (FLAC, WAV, M4A, OGG, Opus, WebM, ...)
#        - MP3s above the threshold bitrate (default 128 kbps, i.e. anything LMS's
#          128 kbps bitrate limit would otherwise transcode on every play)
#      Tags and embedded cover art are kept. Each result is checked (duration must
#      match) before anything is replaced.
#   3. Moves originals to a backup folder outside the music directory (or deletes them
#      with --delete-originals).
#
# music.json is NOT modified: "Song.flac" becomes "Song.mp3" on disk, and Jarvis falls
# back to the .mp3 when the listed file is missing. Keep your local FLACs as masters;
# upload-music.ps1 knows a remote .mp3 stands in for a local .flac.
#
# Usage:
#   ./compress-music.sh --index-only          # just write the report
#   ./compress-music.sh --dry-run             # report + list what would be converted
#   ./compress-music.sh                       # convert
#   ./compress-music.sh -j 2 --threshold 256  # 2 parallel encodes, only re-encode MP3s > 256 kbps
#
# Options:
#   --music-dir DIR       library to process      (default: ~/Music/jarvis-music)
#   --backup-dir DIR      where originals go      (default: ~/Music/jarvis-originals)
#   --report FILE         index output            (default: <music-dir>/../audio-index.tsv)
#   --bitrate KBPS        target MP3 bitrate      (default: 128)
#   --threshold KBPS      re-encode MP3s above    (default: 128)
#   --delete-originals    delete instead of moving originals to the backup folder
#   -j, --jobs N          parallel encodes        (default: 1; encodes run at lowest CPU/IO priority)
#   --index-only | --dry-run

set -uo pipefail

MUSIC_DIR="$HOME/Music/jarvis-music"
BACKUP_DIR="$HOME/Music/jarvis-originals"
REPORT=""
TARGET_KBPS=128
# LMS's bitrate limit transcodes anything above it on every play, so by default
# re-encode every MP3 above the target. Raise this to keep higher-bitrate MP3s.
THRESHOLD_KBPS=128
MODE="convert"
DELETE_ORIGINALS=0
JOBS=1

usage() { sed -n '2,/^$/{s/^# \{0,1\}//;p}' "$0"; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --music-dir)        MUSIC_DIR="$2"; shift 2 ;;
        --backup-dir)       BACKUP_DIR="$2"; shift 2 ;;
        --report)           REPORT="$2"; shift 2 ;;
        --bitrate)          TARGET_KBPS="$2"; shift 2 ;;
        --threshold)        THRESHOLD_KBPS="$2"; shift 2 ;;
        --delete-originals) DELETE_ORIGINALS=1; shift ;;
        -j|--jobs)          JOBS="$2"; shift 2 ;;
        --index-only)       MODE="index"; shift ;;
        --dry-run)          MODE="dry-run"; shift ;;
        -h|--help)          usage 0 ;;
        *) echo "Unknown option: $1" >&2; usage 1 ;;
    esac
done

for tool in ffmpeg ffprobe; do
    command -v "$tool" >/dev/null || { echo "[-] $tool not found — install ffmpeg first." >&2; exit 1; }
done
[[ -d "$MUSIC_DIR" ]] || { echo "[-] Music directory not found: $MUSIC_DIR" >&2; exit 1; }

MUSIC_DIR="$(cd "$MUSIC_DIR" && pwd)"
[[ -n "$REPORT" ]] || REPORT="$(dirname "$MUSIC_DIR")/audio-index.tsv"

# Run encodes at the lowest priority so LMS and Jarvis stay responsive
LOW_PRIORITY=()
command -v nice   >/dev/null && LOW_PRIORITY+=(nice -n 19)
command -v ionice >/dev/null && LOW_PRIORITY+=(ionice -c 3)

# --- Probing -----------------------------------------------------------------

# Sets: P_CODEC P_KBPS P_RATE P_CHANNELS P_DURATION
probe() {
    P_CODEC=""; P_KBPS=0; P_RATE=""; P_CHANNELS=""; P_DURATION=""
    local stream_br="" format_br="" key val
    while IFS='=' read -r key val; do
        val="${val%$'\r'}"; val="${val%\"}"; val="${val#\"}"
        case "$key" in
            streams_stream_0_codec_name)  P_CODEC="$val" ;;
            streams_stream_0_bit_rate)    stream_br="$val" ;;
            streams_stream_0_sample_rate) P_RATE="$val" ;;
            streams_stream_0_channels)    P_CHANNELS="$val" ;;
            format_bit_rate)              format_br="$val" ;;
            format_duration)              P_DURATION="$val" ;;
        esac
    done < <(ffprobe -v error -select_streams a:0 \
                -show_entries stream=codec_name,bit_rate,sample_rate,channels:format=bit_rate,duration \
                -of flat=s=_ "$1" 2>/dev/null)
    # Stream bitrate is exact for CBR; VBR files and containers often only report the overall one
    local br="$stream_br"
    [[ "$br" =~ ^[0-9]+$ ]] || br="$format_br"
    [[ "$br" =~ ^[0-9]+$ ]] && P_KBPS=$(( br / 1000 ))
}

# Prints the action for the most recently probed file: convert | keep | skip-exists | unreadable
decide() {
    local file="$1"
    [[ -z "$P_CODEC" ]] && { echo "unreadable"; return; }
    if [[ "$P_CODEC" == "mp3" ]]; then
        (( P_KBPS > THRESHOLD_KBPS )) && echo "convert" || echo "keep"
        return
    fi
    # Not an MP3: the result will be a new <name>.mp3 next to it
    [[ -e "${file%.*}.mp3" ]] && { echo "skip-exists"; return; }
    echo "convert"
}

# --- Conversion --------------------------------------------------------------

WORK_DIR=""
cleanup() { [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]] && rm -rf "$WORK_DIR"; }
trap cleanup EXIT

duration_matches() {
    local a="$1" b="$2"
    awk -v a="$a" -v b="$b" 'BEGIN { d = a - b; if (d < 0) d = -d; exit !(a > 0 && d <= 2.0) }'
}

stash_original() {
    local src="$1" rel dest
    if (( DELETE_ORIGINALS )); then
        rm -f -- "$src"
        return
    fi
    rel="${src#"$MUSIC_DIR"/}"
    dest="$BACKUP_DIR/$rel"
    mkdir -p -- "$(dirname "$dest")"
    mv -f -- "$src" "$dest"
}

# convert_one SRC SRC_DURATION SRC_RATE — prints one result line: STATUS<TAB>file<TAB>detail
convert_one() {
    local src="$1" src_duration="$2" src_rate="$3"
    local rel="${src#"$MUSIC_DIR"/}"
    local target="${src%.*}.mp3"
    local job_dir tmp
    job_dir="$(mktemp -d "$WORK_DIR/job.XXXXXX")"
    tmp="$job_dir/out.mp3"

    local resample=()
    [[ "$src_rate" =~ ^[0-9]+$ ]] && (( src_rate > 48000 )) && resample=(-ar 44100)

    local common=(-nostdin -v error -y -i "$src" -map_metadata 0 -id3v2_version 3 -write_id3v1 1
                  -c:a libmp3lame -b:a "${TARGET_KBPS}k" "${resample[@]}")

    # First try keeping embedded cover art; fall back to audio only (e.g. WebM with real video)
    if ! "${LOW_PRIORITY[@]}" ffmpeg "${common[@]}" -map 0:a:0 -map '0:v:0?' -c:v copy \
            -disposition:v:0 attached_pic "$tmp" 2>"$job_dir/err1"; then
        if ! "${LOW_PRIORITY[@]}" ffmpeg "${common[@]}" -map 0:a:0 -vn "$tmp" 2>"$job_dir/err2"; then
            printf 'FAILED\t%s\tffmpeg: %s\n' "$rel" "$(head -c 200 "$job_dir/err2" | tr '\n\t' '  ')"
            rm -rf "$job_dir"; return
        fi
    fi

    local new_duration
    new_duration="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$tmp" 2>/dev/null | tr -d '\r')"
    if ! duration_matches "$new_duration" "$src_duration"; then
        printf 'FAILED\t%s\tduration mismatch (%s s vs %s s), original kept\n' "$rel" "$new_duration" "$src_duration"
        rm -rf "$job_dir"; return
    fi

    local old_size new_size
    old_size=$(stat -c %s -- "$src" 2>/dev/null || wc -c < "$src")
    new_size=$(stat -c %s -- "$tmp" 2>/dev/null || wc -c < "$tmp")

    if [[ "$target" == "$src" ]]; then
        # Re-encoding an MP3 in place: move the original out first, put it back on failure
        local backup_path="$BACKUP_DIR/$rel"
        if (( DELETE_ORIGINALS )); then
            mv -f -- "$tmp" "$src" || { printf 'FAILED\t%s\tcould not replace file\n' "$rel"; rm -rf "$job_dir"; return; }
        else
            mkdir -p -- "$(dirname "$backup_path")"
            mv -f -- "$src" "$backup_path" || { printf 'FAILED\t%s\tcould not back up original\n' "$rel"; rm -rf "$job_dir"; return; }
            if ! mv -f -- "$tmp" "$src"; then
                mv -f -- "$backup_path" "$src"
                printf 'FAILED\t%s\tcould not replace file, original restored\n' "$rel"; rm -rf "$job_dir"; return
            fi
        fi
    else
        mv -f -- "$tmp" "$target" || { printf 'FAILED\t%s\tcould not write %s\n' "$rel" "${target##*/}"; rm -rf "$job_dir"; return; }
        stash_original "$src"
    fi
    rm -rf "$job_dir"
    printf 'OK\t%s\t%s -> %s\t%s\t%s\n' "$rel" "$(numfmt --to=iec "$old_size" 2>/dev/null || echo "$old_size")" \
        "$(numfmt --to=iec "$new_size" 2>/dev/null || echo "$new_size")" "$old_size" "$new_size"
}

# --- Main --------------------------------------------------------------------

echo "[*] Library:   $MUSIC_DIR"
echo "[*] Report:    $REPORT"
[[ "$MODE" == "convert" ]] && echo "[*] Originals: $( (( DELETE_ORIGINALS )) && echo "deleted" || echo "$BACKUP_DIR")"
echo "[*] Rule:      non-MP3 -> ${TARGET_KBPS}k MP3; MP3 > ${THRESHOLD_KBPS} kbps -> ${TARGET_KBPS}k MP3"
echo

mapfile -d '' FILES < <(find "$MUSIC_DIR" -type f \( -iname '*.mp3' -o -iname '*.flac' -o -iname '*.wav' \
    -o -iname '*.m4a' -o -iname '*.aac' -o -iname '*.ogg' -o -iname '*.opus' -o -iname '*.webm' \
    -o -iname '*.aif' -o -iname '*.aiff' -o -iname '*.wma' -o -iname '*.ape' -o -iname '*.wv' \) \
    -not -path '*/.*' -print0 | sort -z)

echo "[*] Indexing ${#FILES[@]} files..."
TO_CONVERT=(); TO_CONVERT_DUR=(); TO_CONVERT_RATE=()
declare -A COUNT=() BYTES=()
total_bytes=0; convert_bytes=0; convert_seconds=0

{
    printf 'file\tcodec\tkbps\tsample_rate\tchannels\tduration_s\tsize_mb\taction\n'
    for f in "${FILES[@]}"; do
        probe "$f"
        action="$(decide "$f")"
        size=$(stat -c %s -- "$f" 2>/dev/null || wc -c < "$f")
        rel="${f#"$MUSIC_DIR"/}"
        printf '%s\t%s\t%s\t%s\t%s\t%.1f\t%.2f\t%s\n' "$rel" "${P_CODEC:-?}" "$P_KBPS" "${P_RATE:-?}" \
            "${P_CHANNELS:-?}" "${P_DURATION:-0}" "$(awk -v s="$size" 'BEGIN{print s/1048576}')" "$action"
        COUNT[$action]=$(( ${COUNT[$action]:-0} + 1 ))
        BYTES[$action]=$(( ${BYTES[$action]:-0} + size ))
        total_bytes=$(( total_bytes + size ))
        if [[ "$action" == "convert" ]]; then
            TO_CONVERT+=("$f"); TO_CONVERT_DUR+=("${P_DURATION:-0}"); TO_CONVERT_RATE+=("${P_RATE:-0}")
            convert_seconds=$(awk -v a="$convert_seconds" -v b="${P_DURATION:-0}" 'BEGIN{print a+b}')
        fi
    done
} > "$REPORT"

human() { numfmt --to=iec "$1" 2>/dev/null || echo "$1 bytes"; }
echo "[+] Index written: $REPORT"
for a in keep convert skip-exists unreadable; do
    [[ -n "${COUNT[$a]:-}" ]] && printf '    %-12s %5d files  %8s\n' "$a" "${COUNT[$a]}" "$(human "${BYTES[$a]}")"
done
if (( ${#TO_CONVERT[@]} > 0 )); then
    est=$(awk -v s="$convert_seconds" -v k="$TARGET_KBPS" 'BEGIN{printf "%d", s*k*1000/8}')
    echo "    Converting would shrink $(human "${BYTES[convert]}") to about $(human "$est")."
fi
[[ -n "${COUNT[skip-exists]:-}" ]] && echo "    (skip-exists: an .mp3 with the same name is already there — check the report)"
[[ -n "${COUNT[unreadable]:-}" ]] && echo "    (unreadable: ffprobe found no audio stream — check the report)"
echo

if [[ "$MODE" == "index" ]]; then exit 0; fi
if (( ${#TO_CONVERT[@]} == 0 )); then echo "[+] Nothing to convert."; exit 0; fi

if [[ "$MODE" == "dry-run" ]]; then
    echo "[*] Would convert:"
    for f in "${TO_CONVERT[@]}"; do echo "    ${f#"$MUSIC_DIR"/}"; done
    exit 0
fi

mkdir -p -- "$BACKUP_DIR"
WORK_DIR="$(mktemp -d "$BACKUP_DIR/.work.XXXXXX")"   # outside the music dir, so LMS never sees partial files
RESULTS="$WORK_DIR/results"
: > "$RESULTS"

echo "[*] Converting ${#TO_CONVERT[@]} files with $JOBS job(s)..."
done_count=0
for i in "${!TO_CONVERT[@]}"; do
    while (( $(jobs -rp | wc -l) >= JOBS )); do wait -n; done
    {
        line="$(convert_one "${TO_CONVERT[$i]}" "${TO_CONVERT_DUR[$i]}" "${TO_CONVERT_RATE[$i]}")"
        echo "$line" >> "$RESULTS"
        printf '    [%s] %s\n' "$(cut -f1 <<<"$line")" "$(cut -f2,3 <<<"$line" | tr '\t' ' ')"
    } &
done
wait

ok=$(grep -c '^OK' "$RESULTS" || true)
failed=$(grep -c '^FAILED' "$RESULTS" || true)
saved=$(awk -F'\t' '$1=="OK" { s += $4 - $5 } END { printf "%d", s }' "$RESULTS")
echo
echo "[+] Converted $ok file(s), saved $(human "$saved")."
if (( failed > 0 )); then
    echo "[!] $failed file(s) failed and were left untouched:"
    grep '^FAILED' "$RESULTS" | cut -f2,3 | sed 's/^/    /'
fi
(( DELETE_ORIGINALS )) || echo "[*] Originals are in $BACKUP_DIR — delete it once you're happy with the results."
echo "[*] Jarvis plays files by path, so no LMS rescan is needed for playback (rescan if you browse the library in LMS)."
(( failed == 0 ))
