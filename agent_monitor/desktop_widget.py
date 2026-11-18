"""Native desktop usage window. Run with --demo for an entirely synthetic preview."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import ttk
import webbrowser

from .desktop_controller import PairingSession, demo_summary, present_usage, read_usage
from .remote_agent import CONFIG_FILENAME, default_state_dir

BG, CARD, INK, MUTED, ACCENT = "#f4f6f8", "#ffffff", "#182229", "#687780", "#167d76"


class DesktopWindow:
    def __init__(self, root, state_dir, usage_path, *, demo=False):
        self.root, self.state_dir, self.usage_path, self.demo = root, Path(state_dir), Path(usage_path), demo
        self.stop = threading.Event()
        self.events = queue.Queue(maxsize=32)
        self.session = None
        self.pair_window = None
        self.summary = demo_summary() if demo else None
        self.compact = False
        self.pinned = tk.BooleanVar(value=False)
        self.rendered = None
        root.title("Monitor · 用量")
        root.configure(bg=BG)
        root.geometry("440x690")
        root.minsize(340, 310)
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.option_add("*Font", ("Microsoft YaHei UI", 10))
        style = ttk.Style(root)
        style.theme_use("clam")
        style.configure("Meter.Horizontal.TProgressbar", background=ACCENT, troughcolor="#e9efee",
                        bordercolor="#e9efee", lightcolor=ACCENT, darkcolor=ACCENT, borderwidth=0, thickness=5)
        style.layout("Meter.Horizontal.TProgressbar", [("Horizontal.Progressbar.trough", {
            "sticky": "nswe", "children": [("Horizontal.Progressbar.pbar", {"side": "left", "sticky": "ns"})]})])
        style.layout("Flat.TButton", [("Button.padding", {"sticky": "nswe", "children": [("Button.label", {"sticky": "nswe"})]})])
        style.configure("Flat.TButton", padding=(12, 7), background=CARD, foreground=INK, borderwidth=0)
        style.map("Flat.TButton", background=[("active", "#e4eeeb"), ("pressed", "#d4e7e1")], foreground=[("disabled", MUTED)])
        style.configure("TCheckbutton", background=BG, foreground=MUTED)
        style.map("TCheckbutton", background=[("active", BG)])
        try:
            from PIL import Image, ImageTk
            image_path = Path(__file__).resolve().parent.parent / "assets" / "identity" / "monitor-brand.png"
            self.icon = ImageTk.PhotoImage(Image.open(image_path).resize((64, 64)))
            root.iconphoto(True, self.icon)
        except Exception:
            pass
        header = self.header = tk.Frame(root, bg=BG, padx=22, pady=18)
        header.pack(fill="x")
        self.title = tk.Label(header, text="Monitor", bg=BG, fg=INK, font=("Segoe UI", 23, "bold"))
        self.title.pack(anchor="w")
        self.subtitle = tk.Label(header, text="本机用量  /  演示数据" if demo else "本机用量 · 每 15 秒更新", bg=BG, fg=MUTED)
        self.subtitle.pack(anchor="w", pady=(4, 12))
        toolbar = tk.Frame(header, bg=BG)
        self.toolbar = toolbar
        toolbar.pack(fill="x")
        self.size_button = ttk.Button(toolbar, text="切换小窗", command=self.toggle_size, style="Flat.TButton")
        self.size_button.pack(side="left")
        ttk.Checkbutton(toolbar, text="置顶", variable=self.pinned, command=lambda: root.attributes("-topmost", self.pinned.get())).pack(side="left", padx=10)
        self.pair_button = ttk.Button(toolbar, text="扫码配对", command=self.open_pairing, style="Flat.TButton")
        self.pair_button.pack(side="right")
        if demo:
            self.pair_button.configure(state="disabled")
        elif (self.state_dir / CONFIG_FILENAME).exists():
            self.pair_button.configure(text="已保存配对", state="disabled")
        self.canvas = tk.Canvas(root, bg=BG, highlightthickness=0)
        scrollbar = ttk.Scrollbar(root, orient="vertical", command=self.canvas.yview)
        scrollbar.pack(side="right", fill="y")
        self.canvas.pack(fill="both", expand=True)
        self.canvas.configure(yscrollcommand=scrollbar.set)
        self.content = tk.Frame(self.canvas, bg=BG)
        self.content_id = self.canvas.create_window(0, 0, window=self.content, anchor="nw")
        self.content.bind("<Configure>", lambda _: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(self.content_id, width=e.width))
        self.canvas.bind_all("<MouseWheel>", self.wheel)
        self.footer = tk.Label(root, text="仅显示本机记录", bg=BG, fg=MUTED, pady=8, font=("Microsoft YaHei UI", 9))
        self.footer.pack(fill="x")
        if not demo:
            threading.Thread(target=self.usage_worker, name="monitor-desktop-reader", daemon=True).start()
        root.after(50, self.tick)

    def wheel(self, event):
        if self.root.winfo_containing(event.x_root, event.y_root) is not None:
            self.canvas.yview_scroll(-int(event.delta / 120), "units")

    def emit(self, kind, value):
        try:
            self.events.put_nowait((kind, value))
        except queue.Full:
            pass

    def usage_worker(self):
        while not self.stop.is_set():
            try:
                summary = read_usage(self.usage_path)
            except Exception:
                summary = None  # Never display/log database paths, raw data or exceptions.
            self.emit("usage", summary)
            self.stop.wait(15)

    def tick(self):
        if self.stop.is_set():
            return
        for _ in range(32):
            try:
                kind, value = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "usage":
                self.summary = value
            elif kind == "pair" and value[0] is self.session:
                self.show_pair_state(value[1])
        cards = present_usage(self.summary)
        signature = json.dumps([self.compact, cards], sort_keys=True)
        if signature != self.rendered:
            self.rendered = signature
            self.render(cards)
        self.root.after(500, self.tick)

    def render(self, cards):
        for child in self.content.winfo_children():
            child.destroy()
        if not cards:
            box = tk.Frame(self.content, bg=CARD, padx=24, pady=30)
            box.pack(fill="x", padx=18, pady=5)
            tk.Label(box, text="用量会在这里呈现", bg=CARD, fg=INK, font=("Microsoft YaHei UI", 15, "bold")).pack(anchor="w")
            tk.Label(box, text="尚无可显示的本机记录。\n已有 Monitor 连接器继续采集后会自动更新。", justify="left", wraplength=320, bg=CARD, fg=MUTED).pack(anchor="w", pady=(10, 0))
        for card in cards:
            inset = 12 if self.compact else 22
            box = tk.Frame(self.content, bg=CARD, padx=inset, pady=9 if self.compact else 19)
            box.pack(fill="x", padx=12 if self.compact else 18, pady=(0, 8 if self.compact else 14))
            box.bind("<Configure>", lambda e, panel=box, margin=inset * 2: self.wrap_card(panel, e.width, margin))
            color = ACCENT if card["tool"] == "codex" else "#3b6fd8" if card["tool"] == "antigravity" else "#9b6046"
            heading = tk.Frame(box, bg=CARD) if self.compact else box
            if self.compact:
                heading.pack(fill="x")
                tk.Label(heading, text=card["title"], bg=CARD, fg=color, font=("Segoe UI", 11, "bold")).pack(side="left")
            else:
                tk.Label(box, text=card["title"], bg=CARD, fg=color, font=("Segoe UI", 13, "bold")).pack(anchor="w")
            if card["today_tokens"] is not None:
                if self.compact:
                    tk.Label(heading, text=f"{card['today_tokens']:,}", bg=CARD, fg=INK, font=("Segoe UI", 17, "bold")).pack(side="right")
                else:
                    tk.Label(box, text=f"{card['today_tokens']:,}", bg=CARD, fg=INK, font=("Segoe UI", 27, "bold")).pack(anchor="w", pady=(9, 0))
                tk.Label(box, text="今日已记录 Token · 含缓存", bg=CARD, fg=MUTED, font=("Microsoft YaHei UI", 9)).pack(anchor="e" if self.compact else "w", pady=(0, 5 if self.compact else 12))
            if card["quotas"]:
                tk.Label(box, text="账号额度 · 各窗口已用", bg=CARD, fg=INK, font=("Microsoft YaHei UI", 9 if self.compact else 10, "bold")).pack(anchor="w", pady=(2 if self.compact else 5, 4 if self.compact else 8))
                for quota in card["quotas"]:
                    label = f"{quota['label']}    {quota['used_percent']:g}%" + ("  · 上次观测" if quota["stale"] else "")
                    tk.Label(box, text=label, wraplength=310, justify="left", bg=CARD, fg=MUTED).pack(anchor="w", pady=(0, 3))
                    ttk.Progressbar(box, style="Meter.Horizontal.TProgressbar", value=quota["used_percent"], maximum=100).pack(fill="x", pady=(0, 5 if self.compact else 9))
            if card["models"] and not self.compact:
                tk.Label(box, text="模型构成 · 占本机今日 Token", bg=CARD, fg=INK, font=("Microsoft YaHei UI", 10, "bold")).pack(anchor="w", pady=(12, 8))
                for model in card["models"]:
                    tk.Label(box, text=f"{model['label']}   {model['percent']:.1f}%", wraplength=310, justify="left", bg=CARD, fg=INK).pack(anchor="w", pady=(2, 0))
                    tk.Label(box, text=f"{model['tokens']:,} Token", bg=CARD, fg=MUTED, font=("Segoe UI", 9)).pack(anchor="w", pady=(0, 5))
                tk.Label(box, text="分母含未识别模型；这不是账号额度百分比", wraplength=310, justify="left", bg=CARD, fg=MUTED, font=("Microsoft YaHei UI", 9)).pack(anchor="w", pady=(5, 0))

    @staticmethod
    def wrap_card(panel, width, inset=44):
        for child in panel.winfo_children():
            if isinstance(child, tk.Label):
                child.configure(wraplength=max(140, width - inset))

    def toggle_size(self):
        self.compact = not self.compact
        self.size_button.configure(text="展开详情" if self.compact else "切换小窗")
        self.header.configure(padx=14 if self.compact else 22, pady=7 if self.compact else 18)
        self.title.configure(text="Monitor · 演示" if self.compact and self.demo else "Monitor",
                             font=("Segoe UI", 16 if self.compact else 23, "bold"))
        if self.compact:
            self.subtitle.pack_forget()
            self.pair_button.pack_forget()
        else:
            self.subtitle.pack(anchor="w", pady=(4, 12), before=self.toolbar)
            self.pair_button.pack(side="right")
        self.root.geometry("360x470" if self.compact else "440x690")
        cards = present_usage(self.summary)
        self.rendered = json.dumps([self.compact, cards], sort_keys=True)
        self.render(cards)

    def open_pairing(self):
        if self.demo or (self.state_dir / CONFIG_FILENAME).exists():
            return
        if self.pair_window is not None and self.pair_window.winfo_exists():
            self.pair_window.lift()
            return
        window = self.pair_window = tk.Toplevel(self.root)
        window.title("连接手机")
        window.configure(bg=CARD)
        window.geometry("400x610")
        window.transient(self.root)
        window.protocol("WM_DELETE_WINDOW", self.close_pairing)
        body = tk.Frame(window, bg=CARD, padx=25, pady=23)
        body.pack(fill="both", expand=True)
        tk.Label(body, text="扫码连接，手机确认", bg=CARD, fg=INK, font=("Microsoft YaHei UI", 17, "bold")).pack(anchor="w")
        tk.Label(body, text="使用手机已登录的 Monitor 服务地址", bg=CARD, fg=MUTED).pack(anchor="w", pady=(7, 18))
        self.server = tk.StringVar()
        self.alias = tk.StringVar(value="我的电脑")
        for title, variable in (("服务地址 · HTTPS", self.server), ("电脑别名", self.alias)):
            tk.Label(body, text=title, bg=CARD, fg=MUTED).pack(anchor="w", pady=(7, 4))
            ttk.Entry(body, textvariable=variable).pack(fill="x")
        self.generate = ttk.Button(body, text="生成一次性二维码", command=self.begin_pairing, style="Flat.TButton")
        self.generate.pack(fill="x", pady=(18, 10))
        self.qr_label = tk.Label(body, bg=CARD)
        self.qr_label.pack()
        self.pair_note = tk.Label(body, text="扫码后，在手机核对别名并确认连接。", wraplength=340, justify="left", bg=CARD, fg=MUTED)
        self.pair_note.pack(fill="x", pady=10)
        links = tk.Frame(body, bg=CARD)
        links.pack(fill="x")
        self.copy_button = ttk.Button(links, text="复制确认链接", command=self.copy_link, state="disabled", style="Flat.TButton")
        self.copy_button.pack(side="left")
        self.open_button = ttk.Button(links, text="打开确认页", command=self.open_link, state="disabled", style="Flat.TButton")
        self.open_button.pack(side="right")

    def begin_pairing(self):
        try:
            import qrcode  # noqa: F401 -- check before creating one server request
            from PIL import ImageTk  # noqa: F401
        except ImportError:
            self.pair_note.configure(text="缺少二维码组件，请先安装项目依赖。")
            return
        if self.session:
            self.session.cancel()
        session = self.session = PairingSession(self.state_dir)
        server, alias = self.server.get(), self.alias.get()
        self.generate.configure(state="disabled")
        self.pair_note.configure(text="正在生成二维码…")
        def worker():
            try:
                session.start(server, alias)
            except Exception:
                self.emit("pair", (session, {"status": "failed", "verification_url": None, "seconds_remaining": 0}))
                return
            while not self.stop.is_set():
                state = session.view()
                self.emit("pair", (session, state))
                if state["status"] not in {"pending", "retrying"}:
                    break
                if self.stop.wait(0.5):
                    break
                session.poll()
        threading.Thread(target=worker, name="monitor-desktop-pair", daemon=True).start()

    def show_pair_state(self, state):
        if self.pair_window is None or not self.pair_window.winfo_exists():
            return
        status, url = state["status"], state["verification_url"]
        if url and getattr(self, "qr_url", None) != url:
            import qrcode
            from PIL import ImageTk
            image = qrcode.make(url).convert("RGB")
            image.thumbnail((238, 238))
            self.qr_image = ImageTk.PhotoImage(image)
            self.qr_label.configure(image=self.qr_image)
            self.qr_url = url
        if not url:
            self.qr_url = None
            self.qr_label.configure(image="")
        for button in (self.copy_button, self.open_button):
            button.configure(state="normal" if url else "disabled")
        labels = {"pending": f"等待手机确认 · {state['seconds_remaining']} 秒内有效", "retrying": "暂时无法连接，正在等待重试；二维码到期后停止。",
                  "approved": "配对成功。原有连接器继续负责同步，小组件显示本机用量。", "expired": "二维码已过期。需要时可手动生成新的二维码。",
                  "failed": "未完成配对，请检查 HTTPS 地址、服务版本和手机确认。原有配置未覆盖。",
                  "existing": "已有连接设置，保留原配对。", "cancelled": "本次等待已取消。"}
        self.pair_note.configure(text=labels.get(status, "正在连接…"))
        if status == "approved" and state.get("temporary_cleanup_verified") is False:
            self.pair_note.configure(text="连接已保存。临时文件清理未确认，请勿重复配对。")
        if status in {"approved", "existing"}:
            self.pair_button.configure(text="已保存配对", state="disabled")
        elif status not in {"pending", "retrying", "starting"}:
            self.generate.configure(state="normal")

    def verification_link(self):
        return self.session.view()["verification_url"] if self.session else None

    def copy_link(self):
        url = self.verification_link()
        if url:
            self.root.clipboard_clear()
            self.root.clipboard_append(url)

    def open_link(self):
        url = self.verification_link()
        if url:
            webbrowser.open(url)

    def close_pairing(self):
        if self.session:
            self.session.cancel()
        self.session = None
        self.qr_url = None
        if self.pair_window is not None:
            self.pair_window.destroy()
            self.pair_window = None

    def close(self):
        self.stop.set()
        if self.session:
            self.session.cancel()
        self.root.destroy()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Monitor 桌面用量小组件")
    parser.add_argument("--demo", action="store_true", help="只显示合成演示数据，不访问配置、用量库或网络")
    parser.add_argument("--state-dir", type=Path, default=default_state_dir())
    parser.add_argument("--usage-db", type=Path, help="已有本机匿名 usage.sqlite3；严格只读，不创建")
    args = parser.parse_args(argv)
    usage_path = args.usage_db or args.state_dir / "usage.sqlite3"
    root = tk.Tk()
    DesktopWindow(root, args.state_dir, usage_path, demo=args.demo)
    root.mainloop()


if __name__ == "__main__":
    main()
