#!/usr/bin/env python3
"""Temporary opaque LAN TCP relay to an adb-forwarded local auth port. Logs no traffic."""
import argparse
import concurrent.futures
import ipaddress
import select
import signal
import socket
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen-host", required=True)
    parser.add_argument("--listen-port", required=True, type=int)
    parser.add_argument("--target-port", required=True, type=int)
    args = parser.parse_args()
    address = ipaddress.ip_address(args.listen_host)
    if address.version != 4 or not address.is_private or address.is_unspecified or address.is_loopback:
        parser.error("listen-host must be the explicit private IPv4 LAN interface")
    if not all(1 <= port <= 65535 for port in (args.listen_port, args.target_port)):
        parser.error("ports must be between 1 and 65535")

    stopped = threading.Event()
    slots = threading.BoundedSemaphore(8)
    active = set()
    lock = threading.Lock()
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((str(address), args.listen_port))
    listener.listen(8)
    listener.settimeout(0.5)

    def stop(*_):
        stopped.set()
        listener.close()
        with lock:
            for client in tuple(active):
                try:
                    client.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                client.close()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    def relay(incoming):
        upstream = None
        try:
            upstream = socket.create_connection(("127.0.0.1", args.target_port), timeout=5)
            with lock:
                active.update((incoming, upstream))
            incoming.settimeout(5)
            upstream.settimeout(5)
            peers = {incoming: upstream, upstream: incoming}
            while not stopped.is_set() and peers:
                readable, _, _ = select.select(tuple(peers), (), (), 20)
                if not readable:
                    return
                for source in readable:
                    data = source.recv(16384)
                    destination = peers[source]
                    if not data:
                        try:
                            destination.shutdown(socket.SHUT_WR)
                        except OSError:
                            pass
                        del peers[source]
                    else:
                        destination.sendall(data)
        except (OSError, ValueError):
            pass
        finally:
            for client in (incoming, upstream):
                if client is not None:
                    with lock:
                        active.discard(client)
                    client.close()
            slots.release()

    print(f"Phone authorization relay ready at {address}:{args.listen_port}", flush=True)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8, thread_name_prefix="phone-auth-relay") as pool:
            while not stopped.is_set():
                try:
                    client, _ = listener.accept()
                except socket.timeout:
                    continue
                except OSError:
                    if stopped.is_set():
                        break
                    raise
                if slots.acquire(blocking=False):
                    pool.submit(relay, client)
                else:
                    client.close()
    finally:
        stop()


if __name__ == "__main__":
    main()
