#!/opt/venv/bin/python3
"""simtest - an invented example: checks and runs the Musterfirma's test scripts (.sim).

A script holds one step per line:
    set <name> <number>        sets a value
    add <name> <number>        adds to a value
    expect <name> <number>     the value must be this
    # ...                      a comment
"""
import sys


def steps(path):
    with open(path, encoding="utf-8") as file:
        for number, line in enumerate(file, 1):
            words = line.split()
            if not words or words[0].startswith("#"):
                continue
            yield number, words


def check(path):
    errors = 0
    for number, words in steps(path):
        if words[0] not in ("set", "add", "expect") or len(words) != 3:
            print(f"{path}:{number}: expected 'set|add|expect <name> <number>', got: {' '.join(words)}")
            errors += 1
            continue
        try:
            float(words[2])
        except ValueError:
            print(f"{path}:{number}: '{words[2]}' is not a number")
            errors += 1
    print(f"{path}: {errors} error(s)")
    return errors == 0


def run(path):
    if not check(path):
        return False
    values, failed = {}, 0
    for number, (command, name, value) in steps(path):
        value = float(value)
        if command == "set":
            values[name] = value
        elif command == "add":
            values[name] = values.get(name, 0) + value
        elif values.get(name) != value:
            print(f"{path}:{number}: FAIL {name} is {values.get(name)}, expected {value}")
            failed += 1
    print(f"{path}: {'FAILED' if failed else 'PASSED'}")
    return failed == 0


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("check", "run"):
        sys.exit("usage: simtest check|run <script.sim>")
    sys.exit(0 if (check if sys.argv[1] == "check" else run)(sys.argv[2]) else 1)
