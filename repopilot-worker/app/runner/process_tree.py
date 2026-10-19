"""Windows 进程树管理：用 Job Object 保证"超时后没有幸存子进程"。

为什么需要这个模块
------------------
``taskkill /F /T /PID <pid>`` 只对**调用那一刻已经存在**的子进程有效。
如果 Agent 恰好在被杀的瞬间正在创建子进程（``CreateProcess`` 在途），
那个子进程会在 taskkill 的进程树快照之后才诞生，于是逃逸出清理范围，
在无人看管的情况下继续运行。

本机实测：单次调用约 20% 的概率留下幸存孙进程（CI 跑在 Linux 上，
``os.killpg`` 是内核级进程组 kill，没有这个窗口，所以只在 Windows 暴露）。

Windows 的 Job Object 由内核跟踪整个进程树：把子进程加入 Job，
并设置 ``JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE``，那么**关闭 Job 句柄时，
内核会杀掉组内所有进程**——包括关闭之后才被创建出来的那些。

实现只用标准库 ``ctypes``，不引入新依赖。
Job Object 创建或分配失败时（某些受限环境下不允许嵌套 Job），
调用方退回 ``taskkill /F /T``，行为退化为旧实现，不会因此失败。
"""

from __future__ import annotations

import logging
import os
import subprocess
from typing import Any

log = logging.getLogger(__name__)

# JobObjectExtendedLimitInformation
_JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
# 关闭最后一个 Job 句柄时杀掉组内所有进程
_JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000


class KillOnCloseJob:
    """Windows Job Object：句柄关闭即杀掉整棵进程树。

    用法::

        job = KillOnCloseJob()
        try:
            process = subprocess.Popen(...)
            job.assign(process)
            ...
        finally:
            job.close()

    非 Windows 平台上 :meth:`assign` 与 :meth:`close` 都是空操作，
    进程组清理由调用方用 ``os.killpg`` 完成。
    """

    def __init__(self) -> None:
        self._handle: int | None = None
        self._kernel32: Any = None
        if os.name == "nt":
            self._handle, self._kernel32 = self._create()

    # ------------------------------------------------------------------ 创建

    @staticmethod
    def _create() -> tuple[int | None, Any]:
        try:
            import ctypes
            from ctypes import wintypes

            class _BasicLimit(ctypes.Structure):
                _fields_ = [
                    ("PerProcessUserTimeLimit", ctypes.c_longlong),
                    ("PerJobUserTimeLimit", ctypes.c_longlong),
                    ("LimitFlags", wintypes.DWORD),
                    ("MinimumWorkingSetSize", ctypes.c_size_t),
                    ("MaximumWorkingSetSize", ctypes.c_size_t),
                    ("ActiveProcessLimit", wintypes.DWORD),
                    ("Affinity", ctypes.c_size_t),
                    ("PriorityClass", wintypes.DWORD),
                    ("SchedulingClass", wintypes.DWORD),
                ]

            class _IoCounters(ctypes.Structure):
                _fields_ = [
                    ("ReadOperationCount", ctypes.c_ulonglong),
                    ("WriteOperationCount", ctypes.c_ulonglong),
                    ("OtherOperationCount", ctypes.c_ulonglong),
                    ("ReadTransferCount", ctypes.c_ulonglong),
                    ("WriteTransferCount", ctypes.c_ulonglong),
                    ("OtherTransferCount", ctypes.c_ulonglong),
                ]

            class _ExtendedLimit(ctypes.Structure):
                _fields_ = [
                    ("BasicLimitInformation", _BasicLimit),
                    ("IoInfo", _IoCounters),
                    ("ProcessMemoryLimit", ctypes.c_size_t),
                    ("JobMemoryLimit", ctypes.c_size_t),
                    ("PeakProcessMemoryUsed", ctypes.c_size_t),
                    ("PeakJobMemoryUsed", ctypes.c_size_t),
                ]

            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.CreateJobObjectW.restype = wintypes.HANDLE
            kernel32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
            kernel32.SetInformationJobObject.restype = wintypes.BOOL
            kernel32.SetInformationJobObject.argtypes = [
                wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
            kernel32.AssignProcessToJobObject.restype = wintypes.BOOL
            kernel32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
            kernel32.CloseHandle.restype = wintypes.BOOL
            kernel32.CloseHandle.argtypes = [wintypes.HANDLE]

            handle = kernel32.CreateJobObjectW(None, None)
            if not handle:
                return None, None

            info = _ExtendedLimit()
            info.BasicLimitInformation.LimitFlags = _JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            ok = kernel32.SetInformationJobObject(
                handle, _JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                ctypes.byref(info), ctypes.sizeof(info))
            if not ok:
                kernel32.CloseHandle(handle)
                return None, None
            return int(handle), kernel32
        except Exception as exc:  # pragma: no cover - 只在异常环境走到
            log.debug("创建 Job Object 失败，退回 taskkill: %s", exc)
            return None, None

    # ------------------------------------------------------------------ 使用

    @property
    def available(self) -> bool:
        """Job Object 是否可用；不可用时调用方应退回 ``taskkill``。"""
        return self._handle is not None

    def assign(self, process: subprocess.Popen) -> bool:
        """把进程加入 Job。失败返回 False（调用方退回 taskkill）。"""
        if self._handle is None or self._kernel32 is None:
            return False
        raw = getattr(process, "_handle", None)
        if raw is None:
            return False
        try:
            ok = self._kernel32.AssignProcessToJobObject(self._handle, int(raw))
        except Exception as exc:  # pragma: no cover
            log.debug("AssignProcessToJobObject 失败: %s", exc)
            return False
        if not ok:
            log.debug("AssignProcessToJobObject 返回失败（可能不允许嵌套 Job）")
        return bool(ok)

    def close(self) -> None:
        """关闭 Job 句柄。Windows 上这会杀掉组内所有进程（含事后创建的）。"""
        handle, self._handle = self._handle, None
        if handle is None or self._kernel32 is None:
            return
        try:
            self._kernel32.CloseHandle(handle)
        except Exception as exc:  # pragma: no cover
            log.debug("CloseHandle 失败: %s", exc)
