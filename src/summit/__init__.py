"""Summit: a local vibe map of the music library.

    summit scan                 # index ~/Music/Music/Media.localized
    summit analyze [--limit N] [--sample]
    summit layout               # maps + family › sub-cluster organisation per space
    summit label                # auto-name clusters
    summit app [--browser]      # native window (or server only)
    summit export               # rekordbox XML + m3u8 for all playlists
    summit status
"""

import argparse


def main() -> None:
    ap = argparse.ArgumentParser(prog="summit", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scan")
    a = sub.add_parser("analyze")
    a.add_argument("--limit", type=int)
    a.add_argument("--sample", action="store_true", help="random order (for trial runs)")
    a.add_argument("--workers", type=int, default=5)
    sub.add_parser("layout")
    sub.add_parser("label")
    p = sub.add_parser("app")
    p.add_argument("--browser", action="store_true", help="serve only; open http://127.0.0.1:8765 yourself")
    sub.add_parser("export")
    sub.add_parser("status")
    args = ap.parse_args()

    if args.cmd == "scan":
        from .scan import scan

        scan()
    elif args.cmd == "analyze":
        from .analyze import analyze

        analyze(limit=args.limit, sample=args.sample, workers=args.workers)
    elif args.cmd == "layout":
        from .layout import layout

        layout()
    elif args.cmd == "label":
        from .label import label

        label()
    elif args.cmd == "app":
        from .app import run

        run(browser=args.browser)
    elif args.cmd == "export":
        from . import db
        from .export import export_all

        print(export_all(db.connect()))
    elif args.cmd == "status":
        from . import db

        con = db.connect()
        q = lambda s: con.execute(s).fetchone()[0]
        tracks, analyzed = q("SELECT COUNT(*) FROM tracks WHERE missing=0"), q("SELECT COUNT(*) FROM vectors WHERE kind='clap'")
        laid, playlists = q("SELECT COUNT(DISTINCT track_id) FROM layouts"), q("SELECT COUNT(*) FROM playlists")
        print(f"tracks {tracks} · analyzed {analyzed} · laid out {laid} · playlists {playlists}")
