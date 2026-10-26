#!/bin/sh
# Lays out a 16 GB disk image the way Huginn makes Windows installers, then checks both file
# systems with the standard Linux tools and the kernel: fsck, mount, copy a file in, read it back.
# Needs root, dosfstools and exfatprogs. HUGINN_FORGE_PATH points at a Handheld-Forge checkout.
set -eu
work=$(mktemp -d)
step="start"
cleanup() {
    code=$?
    [ $code -ne 0 ] && echo "::error title=Windows layout test::failed at: $step (exit $code)"
    umount "$work/m1" "$work/m2" 2>/dev/null || true
    [ -n "${loop:-}" ] && losetup -d "$loop" 2>/dev/null
    rm -rf "$work"
}
trap cleanup EXIT
for mode in 0 1500; do
    truncate -s 16G "$work/disk.img"
    python3 - "$work/disk.img" "$mode" <<'PY'
import importlib.machinery, importlib.util, os, sys
sys.path.insert(0, os.environ["HUGINN_FORGE_PATH"])
from handheld_forge.blockdev import FileDisk
loader = importlib.machinery.SourceFileLoader("dt", "helper/huginn-drivetool")
spec = importlib.util.spec_from_loader("dt", loader); dt = importlib.util.module_from_spec(spec); loader.exec_module(dt)
dt.emit = lambda **m: None
d = FileDisk(sys.argv[1]); print(dt.windows_layout(d, int(sys.argv[2]))); d.close()
PY
    step="layout $mode done, attach"
    loop=$(losetup -fP --show "$work/disk.img")
    sleep 1
    step="fsck FAT32 ($mode)"
    fsck.fat -n "${loop}p1"
    step="mount FAT32 ($mode)"
    mkdir -p "$work/m1" "$work/m2"
    mount -t vfat "${loop}p1" "$work/m1"
    step="FAT32 label ($mode)"
    [ "$(blkid -o value -s LABEL "${loop}p1")" = "WINSETUP" ]
    step="FAT32 copy and read back ($mode)"
    head -c 300000000 /dev/urandom > "$work/big.bin"
    mkdir -p "$work/m1/sources/deep" && cp "$work/big.bin" "$work/m1/sources/deep/big.bin"
    umount "$work/m1" && mount -t vfat "${loop}p1" "$work/m1"
    cmp "$work/big.bin" "$work/m1/sources/deep/big.bin"
    umount "$work/m1"
    fsck.fat -n "${loop}p1"
    if [ "$mode" != 0 ]; then
        step="fsck exFAT"
        fsck.exfat -n "${loop}p2"
        step="exFAT label"
        [ "$(blkid -o value -s LABEL "${loop}p2")" = "WININSTALL" ]
        step="mount exFAT, copy, read back"
        mount -t exfat "${loop}p2" "$work/m2"
        cp "$work/big.bin" "$work/m2/big.bin" && umount "$work/m2" && mount -t exfat "${loop}p2" "$work/m2"
        cmp "$work/big.bin" "$work/m2/big.bin"
        umount "$work/m2"
        fsck.exfat -n "${loop}p2"
    fi
    losetup -d "$loop"; loop=
    rm -f "$work/disk.img"
    echo "Windows layout with boot partition $mode MB: OK"
done
