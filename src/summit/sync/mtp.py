"""Minimal ctypes binding to libmtp (brew install libmtp).

Only what the Garmin sync needs: open the first device, list a folder, send,
download and delete files. Struct layouts were checked against libmtp 1.1.23's
header with offsetof(). Not thread-safe: callers hold LOCK.
"""

import ctypes
import ctypes.util
import os
import threading
import time
from ctypes import POINTER, Structure, byref, c_char_p, c_int, c_long, c_uint8, c_uint16, c_uint32, c_uint64, c_void_p

LOCK = threading.RLock()
ROOT = 0xFFFFFFFF  # LIBMTP_FILES_AND_FOLDERS_ROOT

FT_FOLDER = 0
FT_MP3 = 2
FT_WAV = 1
FT_AAC = 30
FT_M4A = 34
FT_UNKNOWN = 44

_EXT_TYPES = {".mp3": FT_MP3, ".m4a": FT_M4A, ".aac": FT_AAC, ".wav": FT_WAV}


class File(Structure):
    pass


File._fields_ = [
    ("item_id", c_uint32),
    ("parent_id", c_uint32),
    ("storage_id", c_uint32),
    ("filename", c_void_p),  # char*, owned by libmtp (freed by destroy_file_t)
    ("filesize", c_uint64),
    ("modificationdate", c_long),
    ("filetype", c_int),
    ("next", POINTER(File)),
]


class Storage(Structure):
    pass


Storage._fields_ = [
    ("id", c_uint32),
    ("StorageType", c_uint16),
    ("FilesystemType", c_uint16),
    ("AccessCapability", c_uint16),
    ("MaxCapacity", c_uint64),
    ("FreeSpaceInBytes", c_uint64),
    ("FreeSpaceInObjects", c_uint64),
    ("StorageDescription", c_char_p),
    ("VolumeIdentifier", c_char_p),
    ("next", POINTER(Storage)),
    ("prev", POINTER(Storage)),
]


class DeviceEntry(Structure):
    _fields_ = [("vendor", c_char_p), ("vendor_id", c_uint16), ("product", c_char_p),
                ("product_id", c_uint16), ("device_flags", c_uint32)]


class RawDevice(Structure):
    _fields_ = [("device_entry", DeviceEntry), ("bus_location", c_uint32), ("devnum", c_uint8)]


# LIBMTP_error_number_t
_DETECT_ERRORS = {
    1: "general error",
    2: "PTP layer error",
    3: "USB error — another app may have the watch open",
    4: "out of memory",
    5: "no MTP device found",
    7: "could not connect — another app may have the watch open",
}


class ErrorEntry(Structure):
    pass


ErrorEntry._fields_ = [("errornumber", c_int), ("error_text", c_char_p), ("next", POINTER(ErrorEntry))]

_lib = None
_libc = ctypes.CDLL(None)
_libc.strdup.restype = c_void_p
_libc.strdup.argtypes = [c_char_p]
_libc.free.argtypes = [c_void_p]


class MTPError(RuntimeError):
    pass


def available():
    return _load() is not None


def _load():
    global _lib
    if _lib is not None:
        return _lib
    path = None
    for cand in ("/opt/homebrew/lib/libmtp.dylib", "/usr/local/lib/libmtp.dylib", ctypes.util.find_library("mtp")):
        if cand and os.path.exists(cand):
            path = cand
            break
    if not path:
        return None
    lib = ctypes.CDLL(path)
    lib.LIBMTP_Init.restype = None
    lib.LIBMTP_Detect_Raw_Devices.argtypes = [POINTER(POINTER(RawDevice)), POINTER(c_int)]
    lib.LIBMTP_Open_Raw_Device_Uncached.restype = c_void_p
    lib.LIBMTP_Open_Raw_Device_Uncached.argtypes = [POINTER(RawDevice)]
    lib.LIBMTP_Release_Device.argtypes = [c_void_p]
    lib.LIBMTP_Get_Storage.argtypes = [c_void_p, c_int]
    lib.LIBMTP_Get_Modelname.restype = c_char_p
    lib.LIBMTP_Get_Modelname.argtypes = [c_void_p]
    lib.LIBMTP_Get_Friendlyname.restype = c_char_p
    lib.LIBMTP_Get_Friendlyname.argtypes = [c_void_p]
    lib.LIBMTP_Get_Serialnumber.restype = c_char_p
    lib.LIBMTP_Get_Serialnumber.argtypes = [c_void_p]
    lib.LIBMTP_Get_Children.argtypes = [c_void_p, c_uint32, c_uint32, POINTER(POINTER(c_uint32))]
    lib.LIBMTP_Get_Filemetadata.restype = POINTER(File)
    lib.LIBMTP_Get_Filemetadata.argtypes = [c_void_p, c_uint32]
    lib.LIBMTP_new_file_t.restype = POINTER(File)
    lib.LIBMTP_destroy_file_t.argtypes = [POINTER(File)]
    lib.LIBMTP_Send_File_From_File.argtypes = [c_void_p, c_char_p, POINTER(File), c_void_p, c_void_p]
    lib.LIBMTP_Get_File_To_File.argtypes = [c_void_p, c_uint32, c_char_p, c_void_p, c_void_p]
    lib.LIBMTP_Delete_Object.argtypes = [c_void_p, c_uint32]
    lib.LIBMTP_Create_Folder.restype = c_uint32
    lib.LIBMTP_Create_Folder.argtypes = [c_void_p, c_char_p, c_uint32, c_uint32]
    lib.LIBMTP_Get_Errorstack.restype = POINTER(ErrorEntry)
    lib.LIBMTP_Get_Errorstack.argtypes = [c_void_p]
    lib.LIBMTP_Clear_Errorstack.argtypes = [c_void_p]
    lib.LIBMTP_Init()
    _lib = lib
    return lib


def _dec(b):
    return b.decode("utf-8", "replace") if b else ""


class Device:
    """An open MTP session. Use as a context manager; release ASAP so macOS
    and the watch are not left with a dangling session."""

    STORAGE_OFFSET = 24  # offsetof(LIBMTP_mtpdevice_t, storage)

    def __init__(self, attempts=3):
        lib = _load()
        if lib is None:
            raise MTPError("libmtp is not installed. Run: brew install libmtp")
        self.lib = lib
        self.dev = None
        reason = "unknown error"
        # The watch can refuse a session for a moment after plugging in, or
        # right after a previous session closed; retry briefly.
        for attempt in range(attempts):
            if attempt:
                time.sleep(2)
            reason = self._open()
            if self.dev:
                break
        if not self.dev:
            raise MTPError(
                f"Could not open the Garmin over MTP ({reason}). If Garmin Express, Android File "
                "Transfer or OpenMTP is running, quit it; otherwise unplug and replug the watch.")
        lib.LIBMTP_Get_Storage(self.dev, 0)

    def _open(self):
        """Uncached open: the cached mode (LIBMTP_Get_First_Device) refuses
        LIBMTP_Get_Files_And_Folders, which is how folders are listed here."""
        raw = POINTER(RawDevice)()
        n = c_int(0)
        rc = self.lib.LIBMTP_Detect_Raw_Devices(byref(raw), byref(n))
        try:
            if rc != 0 or n.value < 1:
                return _DETECT_ERRORS.get(rc, f"error {rc}") if rc else "no MTP device found"
            for i in range(n.value):
                if raw[i].device_entry.vendor_id == 0x091E:
                    pick = i
                    break
            else:
                pick = 0
            self.dev = self.lib.LIBMTP_Open_Raw_Device_Uncached(ctypes.pointer(raw[pick])) or None
            return None if self.dev else "the watch refused the connection"
        finally:
            if raw:
                _libc.free(ctypes.cast(raw, c_void_p))

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def close(self):
        if self.dev:
            self.lib.LIBMTP_Release_Device(self.dev)
            self.dev = None

    def errors(self):
        out = []
        e = self.lib.LIBMTP_Get_Errorstack(self.dev)
        while e:
            out.append(_dec(e.contents.error_text))
            e = e.contents.next
        self.lib.LIBMTP_Clear_Errorstack(self.dev)
        return "; ".join(x for x in out if x) or "unknown MTP error"

    def info(self):
        return {
            "model": _dec(self.lib.LIBMTP_Get_Modelname(self.dev)),
            "name": _dec(self.lib.LIBMTP_Get_Friendlyname(self.dev)),
            "serial": _dec(self.lib.LIBMTP_Get_Serialnumber(self.dev)),
        }

    def storages(self):
        ptr = c_void_p.from_address(self.dev + self.STORAGE_OFFSET).value
        out = []
        s = ctypes.cast(ptr, POINTER(Storage)) if ptr else None
        while s:
            c = s.contents
            out.append({"id": c.id, "capacity": c.MaxCapacity, "free": c.FreeSpaceInBytes,
                        "description": _dec(c.StorageDescription)})
            s = c.next
        return out

    def primary_storage(self):
        st = self.storages()
        if not st:
            raise MTPError("The Garmin reported no storage. Unlock the watch and try again.")
        return st[0]

    def list(self, storage_id, parent_id):
        """[{id, name, size, folder}] for the direct children of parent_id.

        Raw GetObjectHandles + per-object GetObjectInfo. The bulk call
        (LIBMTP_Get_Files_And_Folders) silently returns only a fraction of the
        objects on Garmin watches — on a Forerunner 965 it showed GARMIN/TLG
        and nothing else, hiding Music/ and ~1500 other objects.
        """
        handles = POINTER(c_uint32)()
        n = self.lib.LIBMTP_Get_Children(self.dev, storage_id, parent_id, byref(handles))
        if n < 0:
            raise MTPError(f"listing failed: {self.errors()}")
        out = []
        try:
            for i in range(n):
                f = self.lib.LIBMTP_Get_Filemetadata(self.dev, handles[i])
                if not f:
                    continue
                c = f.contents
                name = _dec(ctypes.string_at(c.filename)) if c.filename else ""
                out.append({"id": c.item_id, "name": name, "size": c.filesize, "folder": c.filetype == FT_FOLDER})
                self.lib.LIBMTP_destroy_file_t(f)
        finally:
            if handles:
                _libc.free(ctypes.cast(handles, c_void_p))
        return out

    def find_folder(self, storage_id, name):
        for item in self.list(storage_id, ROOT):
            if item["folder"] and item["name"].lower() == name.lower():
                return item["id"]
        return None

    def ensure_folder(self, storage_id, name):
        found = self.find_folder(storage_id, name)
        if found:
            return found
        fid = self.lib.LIBMTP_Create_Folder(self.dev, name.encode(), 0, storage_id)
        if not fid:
            raise MTPError(f"Could not create {name}/ on the watch: {self.errors()}")
        return fid

    def send(self, local_path, remote_name, storage_id, parent_id):
        f = self.lib.LIBMTP_new_file_t()
        c = f.contents
        c.filename = _libc.strdup(remote_name.encode("utf-8"))
        c.filesize = os.path.getsize(local_path)
        c.filetype = _EXT_TYPES.get(os.path.splitext(remote_name)[1].lower(), FT_UNKNOWN)
        c.parent_id = parent_id
        c.storage_id = storage_id
        try:
            rc = self.lib.LIBMTP_Send_File_From_File(self.dev, local_path.encode("utf-8"), f, None, None)
            if rc != 0:
                raise MTPError(f"send {remote_name} failed: {self.errors()}")
            return c.item_id
        finally:
            self.lib.LIBMTP_destroy_file_t(f)

    def download(self, item_id, local_path):
        rc = self.lib.LIBMTP_Get_File_To_File(self.dev, item_id, local_path.encode("utf-8"), None, None)
        if rc != 0:
            raise MTPError(f"download failed: {self.errors()}")

    def delete(self, item_id):
        rc = self.lib.LIBMTP_Delete_Object(self.dev, item_id)
        if rc != 0:
            raise MTPError(f"delete failed: {self.errors()}")
