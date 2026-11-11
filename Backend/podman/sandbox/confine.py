"""Starts one program of a session, confined - everything here works without root, capabilities or cgroups, on any
kernel from 4.x on (docs/architecture/code-execution.md):

- resource limits: memory, processes, file size, open files;
- a seccomp filter (it sets no_new_privs, so no setuid program can lift it): no network sockets - only local ones -,
  no ptrace or reading other processes' memory, no leaving the session's process group (the server ends a session by
  killing that group), no signals to the server by any of the system calls that send one, no new namespaces, keyrings,
  BPF or io_uring.

Run by the server with the system's Python (it holds the seccomp module), then replaced by the program:
    python3 -I confine.py --server-pid N --memory-mb M --processes P --file-mb F -- program args...
"""

import argparse
import errno
import os
import resource
import socket
import sys

import seccomp


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-pid", type=int, required=True)
    parser.add_argument("--memory-mb", type=int, required=True)
    parser.add_argument("--processes", type=int, required=True)
    parser.add_argument("--file-mb", type=int, required=True)
    parser.add_argument("program", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    program = args.program[1:] if args.program[:1] == ["--"] else args.program

    limit(resource.RLIMIT_AS, args.memory_mb * 1024 * 1024)
    limit(resource.RLIMIT_NPROC, args.processes)
    limit(resource.RLIMIT_FSIZE, args.file_mb * 1024 * 1024)
    limit(resource.RLIMIT_NOFILE, 256)
    limit(resource.RLIMIT_CORE, 0)
    load_filter(args.server_pid)
    os.execv(program[0], program)


def limit(kind, value):
    resource.setrlimit(kind, (value, value))


def load_filter(server_pid):
    denied = seccomp.ERRNO(errno.EPERM)
    rules = seccomp.SyscallFilter(defaction=seccomp.ALLOW)
    # Local sockets only: Python's multiprocessing and many tools use them; a network socket has nothing to reach.
    rules.add_rule(seccomp.ERRNO(errno.EACCES), "socket", seccomp.Arg(0, seccomp.NE, socket.AF_UNIX))
    for name in ("ptrace", "process_vm_readv", "process_vm_writev", "setsid", "setpgid", "unshare", "setns",
                 "mount", "umount2", "pivot_root", "chroot", "keyctl", "add_key", "request_key", "bpf",
                 "perf_event_open", "userfaultfd", "pidfd_send_signal", "pidfd_getfd", "kcmp",
                 # A signal to one thread by its id alone - the server's threads included; obsolete, nothing uses it.
                 "tkill",
                 # io_uring opens sockets (kernel 5.19+) and runs operations past the rules above.
                 "io_uring_setup", "io_uring_enter", "io_uring_register",
                 # Kernel objects outlive the process and are readable by the same user - a session must leave
                 # nothing for the next. Python's multiprocessing uses neither (its semaphores are files in /dev/shm).
                 "ipc", "shmget", "shmat", "shmdt", "shmctl", "semget", "semop", "semtimedop", "semctl",
                 "msgget", "msgsnd", "msgrcv", "msgctl",
                 "mq_open", "mq_unlink", "mq_timedsend", "mq_timedreceive", "mq_notify", "mq_getsetattr"):
        add(rules, denied, name)
    # The server (PID 1 in its container - the container ends with it), its process group, and every process at once
    # (kill -1): compared on the low 32 bits, where the pid_t argument lives - for every system call that sends a
    # signal by pid or thread group (kill, rt_sigqueueinfo; tgkill, rt_tgsigqueueinfo). The kernel itself drops only
    # the signals PID 1 has no handler for - SIGTERM it handles, so it must not arrive.
    for target in sorted({server_pid, -server_pid, 1, -1}):
        value = target & 0xFFFFFFFF
        for name in ("kill", "rt_sigqueueinfo", "tgkill", "rt_tgsigqueueinfo"):
            add(rules, denied, name, seccomp.Arg(0, seccomp.MASKED_EQ, 0xFFFFFFFF, value))
    rules.load()


def add(rules, action, name, *arguments):
    """A system call this architecture does not have needs no rule."""
    try:
        rules.add_rule(action, name, *arguments)
    except (RuntimeError, ValueError):
        pass


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001 - the server reads this line as the reason the program did not start
        print(f"the sandbox could not start the program: {e}", file=sys.stderr)
        sys.exit(126)
