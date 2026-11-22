"""Measure CPU time, scheduling and memory on the disposable Windows runner."""
import ctypes as c
from ctypes import wintypes as wt
import os

if os.name!="nt" or os.getenv("RUNNER_ENVIRONMENT")!="github-hosted":
    raise RuntimeError("Hosted Windows only")
kernel=c.WinDLL("kernel32",use_last_error=True)
psapi=c.WinDLL("psapi",use_last_error=True)

class Memory(c.Structure):
    _fields_=[("length",wt.DWORD),("load",wt.DWORD)]+[(n,c.c_ulonglong) for n in
        ("physical","free","commitLimit","freeCommit","virtual","freeVirtual","extended")]

class Usage(c.Structure):
    _fields_=[("length",wt.DWORD),("faults",wt.DWORD)]+[(n,c.c_size_t) for n in
        ("peak","working","peakPaged","paged","peakNonPaged","nonPaged","commit","peakCommit","private")]

kernel.OpenProcess.argtypes=[wt.DWORD,wt.BOOL,wt.DWORD]
kernel.OpenProcess.restype=wt.HANDLE
kernel.CloseHandle.argtypes=[wt.HANDLE]
kernel.GetPriorityClass.argtypes=[wt.HANDLE]
kernel.SetPriorityClass.argtypes=[wt.HANDLE,wt.DWORD]
kernel.GetProcessTimes.argtypes=[wt.HANDLE]+[c.POINTER(wt.FILETIME)]*4
kernel.GetProcessAffinityMask.argtypes=[wt.HANDLE,c.POINTER(c.c_size_t),c.POINTER(c.c_size_t)]
psapi.GetProcessMemoryInfo.argtypes=[wt.HANDLE,c.POINTER(Usage),wt.DWORD]
kernel.GlobalMemoryStatusEx.argtypes=[c.POINTER(Memory)]

def process(pid,boost=False):
    handle=kernel.OpenProcess(0x0410 | (0x0200 if boost else 0),False,pid)
    if not handle: return "exited"
    try:
        priority=kernel.GetPriorityClass(handle)
        if boost and priority in (0x20,0x40,0x4000):
            if not kernel.SetPriorityClass(handle,0x8000):
                raise OSError(c.get_last_error(),"Could not test child priority")
        times=[wt.FILETIME() for _ in range(4)]
        assert kernel.GetProcessTimes(handle,*(c.byref(t) for t in times))
        cpu=sum((t.dwHighDateTime<<32)+t.dwLowDateTime for t in times[2:])//10000
        affinity,system=c.c_size_t(),c.c_size_t()
        assert kernel.GetProcessAffinityMask(handle,c.byref(affinity),c.byref(system))
        usage=Usage()
        usage.length=c.sizeof(usage)
        assert psapi.GetProcessMemoryInfo(handle,c.byref(usage),usage.length)
        return f"priority={priority:#x}/{kernel.GetPriorityClass(handle):#x},cpus={affinity.value.bit_count()},cpuMs={cpu},workingMiB={usage.working>>20},privateMiB={usage.private>>20},faults={usage.faults}"
    finally:
        kernel.CloseHandle(handle)

def measure(game_pid,child_pid,phase,boost=False):
    memory=Memory()
    memory.length=c.sizeof(memory)
    assert kernel.GlobalMemoryStatusEx(c.byref(memory))
    print(f"ACOUSTIC_HOST phase={phase} physicalMiB={memory.physical>>20} freeMiB={memory.free>>20} freeCommitMiB={memory.freeCommit>>20} game=({process(game_pid)}) helper=({process(child_pid,boost)})",flush=True)
