import sys
import os
import contextlib
from datetime import datetime

from io import StringIO
try:
    from mypy.dmypy import client
except ImportError as err:
    print(err)
    for p in sys.path:
        print(p)

def main(*args):
    client.main(["--status-file", ".mypy_cache/.dmypy.json", *args])

while True:
    # TODO: proper handling of these args
    # "test.foo::suggest" | "run" | "file.py:1:1:1:1::inspect"
    commands = sys.stdin.readline().strip().split("::")
    print(f"MYPY {commands} {datetime.now().time()}", file=sys.stderr)

    if "run" in commands:
        with contextlib.redirect_stdout(sys.stderr) if "inspect" in commands else contextlib.nullcontext():
            main("run", '--', "--show-error-end", "--no-pretty", "--hide-error-code-links", "--hide-error-context", ".", ".mypy_cache/__mypy_plugin_temp__.py")
        if "inspect" not in commands:
            print("# done!")

    if "suggest" in commands:
        main("suggest", commands[0], "--json")
        print("# done!")

    if "inspect" in commands:
        location = commands[0]

        output = StringIO()
        try:
            with contextlib.redirect_stdout(output):
                main( "inspect", location)
        except BaseException as err:
            print()
            print(f"MYPY result: {output.getvalue().strip()}", file=sys.stderr)
            print(f"MYPY {type(err).__name__}: {err}", file=sys.stderr)
        else:
            print(output.getvalue().strip())
            print(f"MYPY result: {output.getvalue().strip()}", file=sys.stderr)
