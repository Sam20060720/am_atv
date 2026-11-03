"""Exact continuous input windows for offline presentation-gap attribution."""


def merge_windows(windows):
    merged = []
    for start, end in sorted(windows):
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def presentation_intervals_ns(present_timestamps, windows):
    """Keep whole actual-present intervals inside the continuous measured union.

    A slow frame may span several overlapping key windows. Testing each key
    separately would discard exactly those long stalls. Disjoint idle gaps
    remain excluded; no interval is clipped or turned into a synthetic frame.
    """
    merged = merge_windows(windows)
    ordered = sorted(set(present_timestamps))
    return [b - a for a, b in zip(ordered, ordered[1:])
            if any(start <= a < b <= end for start, end in merged)]
