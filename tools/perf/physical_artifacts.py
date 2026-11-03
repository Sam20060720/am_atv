"""Finite exec-out transfers; no sync service, global ADB changes, or protobuf rewriting."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile

PREFIX_LIMIT = 64 * 1024
ARCHIVE_LIMIT = 64 * 1024 * 1024
FILE_LIMIT = 48 * 1024 * 1024


def _exec_bytes(prefix, *arguments, timeout=30, limit=ARCHIVE_LIMIT):
    result = subprocess.run(list(prefix) + ['exec-out', *arguments], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace')[-2000:])
    if len(result.stdout) > limit:
        raise ValueError('Bounded exec-out output limit exceeded')
    return result.stdout


def validated_file_payload(raw, expected_size, expected_sha256):
    """A suffix is accepted only when BOTH remote length and SHA prove every byte."""
    if not 0 < expected_size <= FILE_LIMIT or not re.fullmatch(r'[a-f0-9]{64}', expected_sha256):
        raise ValueError('Invalid bounded remote file identity')
    prefix_size = len(raw) - expected_size
    if not 0 <= prefix_size <= PREFIX_LIMIT:
        raise ValueError('Incomplete file or excessive transport prefix')
    payload = raw[prefix_size:]
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise ValueError('Transferred binary differs from remote SHA-256; refusing to trim/repair it')
    return payload, prefix_size


def _archive_payload(raw, expected_root):
    if not raw or len(raw) > ARCHIVE_LIMIT:
        raise ValueError('Invalid bounded archive length')
    # A vendor stdout prefix can unalign tar blocks. Locate only a validated
    # first header, bounded to the prefix, then require this exact root below.
    candidates = [0]
    at = raw.find(b'ustar', 257, min(len(raw), PREFIX_LIMIT + 262))
    while at >= 0:
        offset = at - 257
        if offset not in candidates:
            candidates.append(offset)
        at = raw.find(b'ustar', at + 1, min(len(raw), PREFIX_LIMIT + 262))
    for offset in candidates:
        payload = raw[offset:]
        if len(payload) < 1536 or len(payload) % 512:
            continue
        try:
            with tarfile.open(fileobj=io.BytesIO(payload), mode='r:') as archive:
                members = archive.getmembers()
                if not members or not members[0].isdir() or PurePosixPath(members[0].name).parts != (expected_root,):
                    continue
                if len(payload) - archive.offset < 1024 or any(payload[archive.offset:]):
                    continue
                return payload, offset
        except (tarfile.TarError, ValueError):
            continue
    raise ValueError('No complete checksum-valid tar archive for the requested result directory')


def extract_result_archive(raw, output_parent, expected_root):
    """Validate all members, then atomically publish a fresh regular-file-only tree."""
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', expected_root) or expected_root in ('.', '..'):
        raise ValueError('Invalid expected result directory')
    output_parent = Path(output_parent)
    destination = output_parent / expected_root
    if destination.exists() or destination.is_symlink():
        raise FileExistsError(destination)
    payload, prefix_size = _archive_payload(raw, expected_root)
    with tarfile.open(fileobj=io.BytesIO(payload), mode='r:') as archive:
        members = archive.getmembers()
        if len(members) > 512:
            raise ValueError('Too many archive members')
        paths = set()
        total = 0
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != expected_root or '\\' in member.name:
                raise ValueError('Unsafe archive member path')
            if path in paths:
                raise ValueError('Duplicate archive member')
            paths.add(path)
            if not (member.isdir() or member.isreg()) or member.issparse():
                raise ValueError('Archive links/devices/sparse/special files are forbidden')
            if member.size < 0 or member.size > FILE_LIMIT:
                raise ValueError('Archive member size exceeds bound')
            total += member.size
        if total > ARCHIVE_LIMIT:
            raise ValueError('Expanded archive exceeds bound')
        output_parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.physical-results-', dir=output_parent) as staging:
            staging = Path(staging)
            for member in members:
                target = staging.joinpath(*PurePosixPath(member.name).parts)
                target.resolve().relative_to(staging.resolve())
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    source = archive.extractfile(member)
                    if source is None:
                        raise ValueError('Archive regular file body missing')
                    with source, target.open('xb') as stream:
                        shutil.copyfileobj(source, stream, length=64 * 1024)
                    if target.stat().st_size != member.size:
                        raise ValueError('Archive regular file truncated')
            (staging / expected_root).rename(destination)
    return dict(method='bounded_exec_out_tar_regular_files_only', prefix_bytes=prefix_size,
                archive_bytes=len(payload), member_count=len(members), expanded_bytes=total)


def download_result_directory(prefix, remote, output_parent, run_id, timeout=35):
    path = PurePosixPath(remote)
    if path.name != run_id:
        raise ValueError('Requested result root differs from remote path')
    raw = _exec_bytes(prefix, 'tar', '-C', str(path.parent), '-cf', '-', run_id,
                      timeout=timeout, limit=ARCHIVE_LIMIT)
    proof = extract_result_archive(raw, output_parent, run_id)
    Path(output_parent, run_id + '-transfer.json').write_text(json.dumps(proof, indent=2))
    return proof


def download_exact_file(prefix, remote, destination, timeout=35):
    destination = Path(destination)
    if destination.exists():
        raise FileExistsError(destination)
    size_text = _exec_bytes(prefix, 'stat', '-c', '%s', remote, timeout=10, limit=PREFIX_LIMIT).decode(errors='strict')
    sizes = [int(line) for line in size_text.splitlines() if re.fullmatch(r'\d+', line.strip())]
    if len(sizes) != 1:
        raise ValueError('Remote file size has no unique numeric witness')
    hashes = _exec_bytes(prefix, 'sha256sum', remote, timeout=15, limit=PREFIX_LIMIT).decode(errors='strict')
    matches = re.findall(r'(?m)^([a-f0-9]{64})\s+\*?' + re.escape(remote) + r'\s*$', hashes)
    if len(matches) != 1:
        raise ValueError('Remote file SHA has no unique path-matched witness')
    raw = _exec_bytes(prefix, 'cat', remote, timeout=timeout, limit=FILE_LIMIT + PREFIX_LIMIT)
    payload, prefix_size = validated_file_payload(raw, sizes[0], matches[0])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(payload)
    proof = dict(method='bounded_exec_out_cat_remote_size_and_sha256', bytes=len(payload),
                 sha256=matches[0], prefix_bytes=prefix_size, binary_rewritten=False)
    destination.with_suffix(destination.suffix + '.transfer.json').write_text(json.dumps(proof, indent=2))
    return proof
