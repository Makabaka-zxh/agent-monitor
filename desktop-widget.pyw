"""Windowed Windows launcher: no console, credential logs or automatic installs."""
if __name__ == "__main__":
    try:
        from agent_monitor.desktop_widget import main
        main()
    except Exception:
        # Neither raw exception text nor paths are shown: either may contain
        # account-specific data. Dependencies are installed by the user/setup.
        import tkinter as tk
        from tkinter import messagebox
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Monitor", "小组件未能启动。请检查 Python 的 Tk 组件和项目依赖是否已安装。")
        root.destroy()
