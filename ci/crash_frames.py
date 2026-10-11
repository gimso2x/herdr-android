#!/usr/bin/env python3
"""Print the crashing thread's top frames (module + function names only) of the newest emulator minidump.

CI diagnostics for a vanished emulator host process (public repository, job log only): a dump can hold
memory of the guest and the page, so NOTHING but the exception code, module basenames and symbol names is
printed. No pip dependency: a minimal reader for the MDMP streams (thread list, module list, exception,
AMD64 context), a heuristic stack scan for the return addresses, and `nm` for the names.

usage: ci/crash_frames.py [crash-db-dir ...]    (default: /tmp/android-runner/emu-crash-*.db)
"""
import bisect
import glob
import os
import struct
import subprocess
import sys

MAX_FRAMES = 14
SCAN_BYTES = 64 * 1024


def u32(b, o): return struct.unpack_from('<I', b, o)[0]
def u64(b, o): return struct.unpack_from('<Q', b, o)[0]


def parse(path):
    b = open(path, 'rb').read()
    if b[:4] != b'MDMP':
        raise ValueError('not a minidump')
    count, dir_rva = u32(b, 8), u32(b, 12)
    streams = {}
    for i in range(count):
        t, size, rva = struct.unpack_from('<III', b, dir_rva + 12 * i)
        streams[t] = (size, rva)
    modules = []
    if 4 in streams:
        rva = streams[4][1]
        for i in range(u32(b, rva)):
            o = rva + 4 + 108 * i
            base, size = u64(b, o), u32(b, o + 8)
            nrva = u32(b, o + 20)
            n = u32(b, nrva)
            name = b[nrva + 4:nrva + 4 + n].decode('utf-16-le', 'replace')
            modules.append((base, size, name))
    threads = {}
    if 3 in streams:
        rva = streams[3][1]
        for i in range(u32(b, rva)):
            o = rva + 4 + 48 * i
            tid = u32(b, o)
            stack_start, stack_size, stack_rva = u64(b, o + 24), u32(b, o + 32), u32(b, o + 36)
            ctx_size, ctx_rva = u32(b, o + 40), u32(b, o + 44)
            threads[tid] = (stack_start, b[stack_rva:stack_rva + stack_size], b[ctx_rva:ctx_rva + ctx_size])
    exc = None
    if 6 in streams:
        rva = streams[6][1]
        tid = u32(b, rva)
        code, addr = u32(b, rva + 8), u64(b, rva + 24)
        ctx_size, ctx_rva = u32(b, rva + 160), u32(b, rva + 164)
        exc = (tid, code, addr, b[ctx_rva:ctx_rva + ctx_size])
    return modules, threads, exc


def symbols(path, cache={}):
    """Sorted [(value, name)] from nm (symtab, else dynamic), plus whether the ELF is position independent."""
    if path in cache:
        return cache[path]
    pic = False
    try:
        with open(path, 'rb') as f:
            head = f.read(20)
        pic = head[:4] == b'\x7fELF' and struct.unpack_from('<H', head, 16)[0] == 3
    except OSError:
        cache[path] = ([], False)
        return cache[path]
    syms = []
    for flags in (['-n', '-C', '--defined-only'], ['-D', '-n', '-C', '--defined-only']):
        try:
            out = subprocess.run(['nm'] + flags + [path], capture_output=True, text=True, timeout=60).stdout
        except (OSError, subprocess.TimeoutExpired):
            continue
        for line in out.splitlines():
            parts = line.split(' ', 2)
            if len(parts) == 3 and parts[1] in 'tTwWiI':
                try:
                    syms.append((int(parts[0], 16), parts[2]))
                except ValueError:
                    pass
        if syms:
            break
    cache[path] = (syms, pic)
    return cache[path]


def locate(addr, modules):
    for base, size, name in modules:
        if base <= addr < base + size:
            syms, pic = symbols(name)
            key = addr - base if pic else addr
            i = bisect.bisect_right([s[0] for s in syms], key) - 1
            fn = syms[i][1][:110] if i >= 0 and key - syms[i][0] < 0x10000 else '?'
            return '%s!%s+0x%x' % (os.path.basename(name), fn, addr - base)
    return None


def newest_dump(dirs):
    dumps = []
    for d in dirs:
        dumps += glob.glob(os.path.join(d, '**', '*.dmp'), recursive=True)
    return max(dumps, key=os.path.getmtime) if dumps else None


def main():
    dirs = sys.argv[1:] or glob.glob('/tmp/android-runner/emu-crash-*.db')
    for d in dirs:
        files = [(os.path.relpath(os.path.join(r, f), d), os.path.getsize(os.path.join(r, f)))
                 for r, _, fs in os.walk(d) for f in fs]
        print('crash db %s: %s' % (os.path.basename(d), ', '.join('%s %dB' % x for x in sorted(files)[:12]) or 'no files'))
    dump = newest_dump(dirs)
    if not dump:
        print('no minidump (*.dmp) found')
        return 0
    modules, threads, exc = parse(dump)
    if not exc:
        print('minidump has no exception stream; threads=%d modules=%d' % (len(threads), len(modules)))
        return 0
    tid, code, addr, ctx = exc
    print('exception code=0x%x address=0x%x (null-page=%s) thread=%d of %d threads, %d modules'
          % (code, addr, addr < 0x10000, tid, len(threads), len(modules)))
    if len(ctx) < 0x100:
        print('no AMD64 context in the exception stream')
        return 0
    rsp, rip = u64(ctx, 0x98), u64(ctx, 0xf8)
    frames = [rip]
    stack_start, stack, _ = threads.get(tid, (0, b'', b''))
    if stack and stack_start <= rsp < stack_start + len(stack):
        off = rsp - stack_start
        blob = stack[off:off + SCAN_BYTES]
        for i in range(0, len(blob) - 7, 8):
            v = u64(blob, i)
            if locate_module(v, modules) and v != frames[-1]:
                frames.append(v)
            if len(frames) >= MAX_FRAMES:
                break
    else:
        print('stack memory of the crashing thread is not in the dump')
    for n, a in enumerate(frames):
        print('  #%d %s%s' % (n, locate(a, modules) or '?', '' if n == 0 else '  (stack scan)'))
    return 0


def locate_module(addr, modules):
    return any(base <= addr < base + size for base, size, _ in modules)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as e:  # diagnostics must never fail the job
        print('crash_frames failed: %s: %s' % (type(e).__name__, str(e)[:160]))
        sys.exit(0)
