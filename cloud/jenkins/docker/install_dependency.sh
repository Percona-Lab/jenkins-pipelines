#!/bin/bash
set -euo pipefail

url="$1"
verify="${2:-1}"
checksum_url="${3:-}"
strip_components="${4:-1}"

file="$(basename "$url")"
bin_folder="$HOME/.local/bin"
verified_checksum=0

verify_checksum() {
    local local_checksum_url="$1"
    local checksum_file="checksum_file"
    if curl -fsSL "$local_checksum_url" -o "$checksum_file"; then
        if grep -q " " "$checksum_file"; then
            new_checksum=$(grep -i "$(basename "$url")" "$checksum_file" | awk '{print $1}')
            echo "$new_checksum" > "$checksum_file"  
        fi
        echo "$(cat $checksum_file) $file" > "$checksum_file"
        echo "Filtered checksum file is: $(cat $checksum_file)"
        sha256sum -c "$checksum_file" && verified_checksum=1
    fi
}

checksum_verification() {
    echo "Verifying checksum"
    if [[ -n "$checksum_url" ]]; then
        verify_checksum "$checksum_url"
    else
        for type in sha256 sha256sum sha256.txt; do
            verify_checksum "$url.$type"
            [[ $verified_checksum -eq 1 ]] && break
        done
    fi
    [[ $verified_checksum -eq 1 ]] && echo "Checksum verified" || { echo "Checksum mismatch!"; exit 1; }
}

echo "Downloading $url"
curl -fsSL "$url" -o "$file"
[[ ! -f "$file" ]] && echo "File not downloaded!" && exit 1
[[ "$verify" == "1" ]] && checksum_verification

mkdir -p "$bin_folder"
if [[ "$file" == *.tar.gz ]]; then
    echo "Extracting $file → $bin_folder"
    tar -xzf "$file" -C "$bin_folder" --strip-components="$strip_components"
    echo "Extracted into $bin_folder"
else
    echo "Installing $file → $bin_folder"
    mv "$file" "$bin_folder/" && chmod 755 "$bin_folder/$file"
fi
