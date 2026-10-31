#!/bin/sh
# Fetches Huginn's fonts (SIL Open Font License) into fonts/. CI runs this before every build.
set -eu
cd "$(dirname "$0")/../../huginn"
mkdir -p fonts
base=https://raw.githubusercontent.com/google/fonts/main/ofl
get() { curl -sSfL --retry 3 -o "fonts/$2" "$base/$1"; }
get "cinzel/Cinzel%5Bwght%5D.ttf" Cinzel.ttf
get "cinzel/OFL.txt" OFL-Cinzel.txt
get "barlow/Barlow-Regular.ttf" Barlow-Regular.ttf
get "barlow/Barlow-Medium.ttf" Barlow-Medium.ttf
get "barlow/Barlow-SemiBold.ttf" Barlow-SemiBold.ttf
get "barlow/Barlow-Bold.ttf" Barlow-Bold.ttf
get "barlow/OFL.txt" OFL-Barlow.txt
get "notosansrunic/NotoSansRunic-Regular.ttf" NotoSansRunic-Regular.ttf
get "notosansrunic/OFL.txt" OFL-NotoSansRunic.txt
ls fonts
