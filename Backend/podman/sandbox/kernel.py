"""A session's Python, like a notebook: one namespace for every call, the value of a last expression shown. Reads one
JSON line {"code": ...} per call from stdin and answers with one JSON line {"output": ..., "error": ...}. Runs confined
(confine.py) in the session's directory:
    python3 -I -u kernel.py <output limit in bytes>
"""

import ast
import json
import multiprocessing
import os
import sys
import tempfile
import traceback
import types


def main():
    limit = int(sys.argv[1])
    # The answers go over a descriptor of their own: fd 1 and 2 become a file every program of the code inherits - a
    # subprocess, a forked worker, a C library's warning - so that nothing but this kernel ever writes an answer line.
    answers = os.fdopen(os.dup(1), "w")
    output = tempfile.TemporaryFile()
    os.dup2(output.fileno(), 1)
    os.dup2(output.fileno(), 2)
    # The code runs in a module of its own that is __main__ - as in a notebook, multiprocessing (and pickle) then find
    # the functions it defines there, not in this file. Fork, not Python 3.14's forkserver: a worker then has them
    # without importing anything; the kernel runs no threads of its own.
    notebook = types.ModuleType("__main__")
    sys.modules["__main__"] = notebook
    multiprocessing.set_start_method("fork")
    namespace = notebook.__dict__
    calls = sys.stdin
    # The code reads nothing: input() would take the next call.
    sys.stdin = open(os.devnull)

    for line in calls:
        answers.write(json.dumps(run(json.loads(line)["code"], namespace, output, limit)) + "\n")
        answers.flush()


def run(code, namespace, output, limit):
    output.seek(0)
    output.truncate()
    error = None
    try:
        tree = ast.parse(code)
        last = None
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            last = ast.Expression(tree.body.pop().value)
        exec(compile(tree, "<code>", "exec"), namespace)
        if last is not None:
            value = eval(compile(last, "<code>", "eval"), namespace)
            if value is not None:
                print(repr(value))
    except BaseException:  # noqa: BLE001 - the model gets every error as text, the session goes on
        error = traceback.format_exc(limit=8)
    flush()
    return {"output": read_output(output, limit), "error": error}


def flush():
    """What the code printed through Python's own buffers - it may have replaced or closed them."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.flush()
        except Exception:  # noqa: BLE001
            pass


def read_output(output, limit):
    """What was written to fd 1 and 2 during the call, at most the limit - a program printing a whole file must not
    fill the server's memory with it."""
    output.seek(0)
    data = output.read(limit)
    size = os.fstat(output.fileno()).st_size
    text = data.decode("utf-8", errors="replace")
    if size > len(data):
        text += f"\n[... {size - len(data)} more bytes cut]"
    return text


if __name__ == "__main__":
    main()
