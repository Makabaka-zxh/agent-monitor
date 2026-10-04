"""Local-only account creation for a server behind an HTTPS proxy."""
import argparse
import getpass
from pathlib import Path

from .server import Login
from .store import Store


def main():
    parser = argparse.ArgumentParser(description="在同步服务主机建立个人 App 账号")
    parser.add_argument("--username", required=True)
    parser.add_argument("--state-dir", type=Path, default=Path(".state/hub"))
    args = parser.parse_args()
    store = Store(args.state_dir / "monitor.sqlite3")
    try:
        if store.has_account():
            parser.error("账号已存在，请使用现有账号登录")
        first = getpass.getpass("Password (12-128 characters): ")
        second = getpass.getpass("Confirm password: ")
        if first != second:
            parser.error("两次密码不一致")
        credentials = Login(username=args.username, password=first)
        store.setup(credentials.username, credentials.password)
        print("App 账号已建立，可在手机登录。")
    finally:
        store.close()


if __name__ == "__main__":
    main()
