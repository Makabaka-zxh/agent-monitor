#!/usr/bin/env python3
"""Run updater host integration tests against the current checkout, without an SDK.

Android boundaries are synthetic. These tests do not install an APK or establish
that Android will deliver the lifecycle/permission events simulated here.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ANDROID = Path(__file__).resolve().parent
SUITES = {
    "lifecycle": (
        "update-lifecycle-stubs",
        "NativeUpdateActivityLifecycleTest",
        ("NativeUpdateActivity", "UpdatePolicy", "UpdateRecoveryPolicy", "UpdatePackage"),
    ),
    "package-provider": (
        "update-package-stubs",
        "UpdatePackageProviderIntegrationTest",
        ("UpdatePolicy", "UpdateRecoveryPolicy", "UpdatePackage", "UpdateInstallProvider", "UpdateClient"),
    ),
}


def java_tools(java_home):
    suffix = ".exe" if os.name == "nt" else ""
    if java_home:
        binaries = [Path(java_home).expanduser().resolve() / "bin" / (name + suffix)
                    for name in ("javac", "java")]
        if not all(path.is_file() for path in binaries):
            raise RuntimeError("--java-home / JAVA_HOME must point to a complete JDK (17 recommended)")
        return binaries
    binaries = [shutil.which(name) for name in ("javac", "java")]
    if not all(binaries):
        raise RuntimeError("Set JAVA_HOME, pass --java-home, or put javac and java on PATH")
    return [Path(path).resolve() for path in binaries]


def run_suite(name, javac, java, root):
    stub_folder, test_class, production = SUITES[name]
    suite_root = root / name
    classes = suite_root / "classes"
    classes.mkdir(parents=True)
    sources = sorted((ANDROID / "tests" / stub_folder).rglob("*.java"))
    if not sources:
        raise RuntimeError("Missing test boundary sources: " + stub_folder)
    sources += [ANDROID / "src" / "com" / "agentmonitor" / "live" / (item + ".java")
                for item in production]
    sources.append(ANDROID / "tests" / (test_class + ".java"))
    print("Running updater host suite: " + name, flush=True)
    # Separate, fresh classpaths prevent either suite's Android doubles from
    # shadowing the other suite or entering build/classes and the application.
    subprocess.run([str(javac), "-encoding", "UTF-8", "--release", "8",
                    "-d", str(classes), *map(str, sources)], check=True, timeout=60,
                   cwd=suite_root)
    fixture_root = suite_root / "fixtures"
    if name == "package-provider":
        fixture_root.mkdir()
    subprocess.run([str(java), "-cp", str(classes), "com.agentmonitor.live." + test_class,
                    str(fixture_root)], check=True, timeout=60, cwd=suite_root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"),
                        help="JDK directory; defaults to JAVA_HOME, then PATH")
    parser.add_argument("--suite", choices=("all", *SUITES), default="all")
    args = parser.parse_args()
    try:
        javac, java = java_tools(args.java_home)
        # Only the newly-created temporary directory is ever cleaned up. It
        # contains synthetic files, never an existing cache or application data.
        with tempfile.TemporaryDirectory(prefix="monitor-update-tests-") as temporary:
            root = Path(temporary).resolve()
            for name in SUITES if args.suite == "all" else (args.suite,):
                run_suite(name, javac, java, root)
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print("Updater host checks failed: " + str(error), file=sys.stderr)
        return 1
    print("Updater host integration checks passed (synthetic Android boundaries; no device acceptance).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
