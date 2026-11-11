# Musterfirma's test tools

The company's test scripts are `.sim` files (one step per line: `set <name> <number>`, `add <name> <number>`,
`expect <name> <number>`, `#` for a comment) and Python test scripts for pytest.

- `simtest check data/<script>.sim` - checks a script's syntax, names every wrong line as `<file>:<line>: ...`.
- `simtest run data/<script>.sim` - runs it: `FAIL` per expectation not met, then `PASSED` or `FAILED`; exit code 1
  when it failed.
- `python3 -m pytest -q data/<tests>.py` - runs the Python test scripts (pytest is importable in run_python too).

When the user sends a script, check it before you run it, and explain a failure by the script's line.
