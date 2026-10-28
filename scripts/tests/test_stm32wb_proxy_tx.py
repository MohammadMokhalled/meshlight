# SPDX-License-Identifier: MIT
"""Run the actual SDK send/callback functions against controlled bearer results.

Requires ZEPHYR_BASE and a native C compiler (CC, or clang/cc on PATH).
No radio or installed SDK files are modified.
"""
import ctypes
import _ctypes
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def function(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


FIXTURE = r"""
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#define LOG_DBG(...) ((void)0)
#define CONFIG_BT_MESH_STATISTIC 0
#define IS_ENABLED(x) (x)
#define BT_MESH_TRANSMIT_COUNT(x) ((x) & 7)
#define BT_MESH_TRANSMIT_INT(x) ((((x) >> 3) + 1) * 10)
struct bt_mesh_send_cb {
    void (*start)(uint16_t, int, void *);
    void (*end)(int, void *);
};
struct bt_mesh_adv_ctx {
    const struct bt_mesh_send_cb *cb;
    void *cb_data;
    unsigned type, started, xmit;
};
struct bt_mesh_adv {
    struct bt_mesh_adv_ctx ctx;
    struct { uint8_t *data; unsigned len; } b;
};
struct bt_mesh_ext_adv { struct bt_mesh_adv *adv; };
struct bt_data { uint8_t type; unsigned data_len; const uint8_t *data; };
static const uint8_t bt_mesh_adv_type[] = {0x29, 0x2a, 0x2b, 0x24};
static unsigned starts, ends, refs, progress;
static int result, start_error, end_error;
static void *expected_data;
static void bt_mesh_stat_succeeded_count(struct bt_mesh_adv_ctx *ctx) {}
static struct bt_mesh_adv *bt_mesh_adv_ref(struct bt_mesh_adv *adv) { refs++; return adv; }
static int bt_data_send(struct bt_mesh_ext_adv *ext, uint8_t n, uint16_t interval,
                       const struct bt_data *ad, size_t count) { return result; }
static void started(uint16_t duration, int error, void *data) {
    starts++; start_error = error;
    if (error) { progress++; }
    if (data != expected_data) { starts += 100; }
}
static void ended(int error, void *data) {
    ends++; end_error = error; progress++;
    if (data != expected_data) { ends += 100; }
}
static const struct bt_mesh_send_cb callbacks = {started, ended};
"""

CASES = r"""
#define CHECK(x) do { if (!(x)) return __LINE__; } while (0)
#ifdef _WIN32
__declspec(dllexport)
#endif
int run_case(int scenario, int fixed) {
    struct bt_mesh_adv adv = {.ctx = {.cb = &callbacks}};
    struct bt_mesh_ext_adv ext = {0};
    starts = ends = refs = progress = 0;
    expected_data = &ext;
    adv.ctx.cb_data = expected_data;
    result = scenario == 2 ? 0 : (scenario == 3 ? -22 : -13);
    if (scenario != 1) { bt_mesh_adv_send_start(0, 0, &adv.ctx); }
    int error = fixed ? fixed_adv_send(&ext, &adv) : old_adv_send(&ext, &adv);
    CHECK(error == result);
    CHECK(starts == 1);
    if (scenario == 2) {
        CHECK(refs == 1 && ext.adv == &adv);
        CHECK(ends == 0 && progress == 0);
        bt_mesh_adv_send_end(0, &adv.ctx);
        CHECK(ends == 1 && progress == 1 && end_error == 0);
    } else if (scenario == 1) {
        CHECK(start_error == result && adv.ctx.cb == NULL);
        CHECK(ends == 0 && progress == 1 && refs == 0);
    } else {
        CHECK(start_error == 0 && refs == 0);
        CHECK(ends == 1 && end_error == result && progress == 1);
    }
    return 0;
}
"""


class ProxyTransmitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        base = os.environ.get("ZEPHYR_BASE")
        compiler = os.environ.get("CC") or shutil.which("clang") or shutil.which("cc")
        if not base or not compiler:
            raise unittest.SkipTest("ZEPHYR_BASE and a native C compiler are required")
        mesh = Path(base) / "subsys/bluetooth/mesh"
        original = (mesh / "adv_ext.c").read_text()
        patch = (ROOT / "firmware/cmake/stm32wb_proxy_tx.cmake").read_text()
        old, new = [re.search(r"set\(proxy_tx_" + name + r" \[=\[(.*?)\]=\]\)",
                              patch, re.S).group(1) for name in ("old", "new")]
        if original.count(old) != 1:
            raise AssertionError("SDK advertiser changed; backport must be reviewed")
        corrected = original.replace(old, new)
        callbacks_source = (mesh / "adv.c").read_text()
        source = FIXTURE + "\n" + "\n".join([
            function(callbacks_source, "void bt_mesh_adv_send_start("),
            function(callbacks_source, "void bt_mesh_adv_send_end("),
            function(original, "static int adv_send(").replace("adv_send(", "old_adv_send(", 1),
            function(corrected, "static int adv_send(").replace("adv_send(", "fixed_adv_send(", 1),
        ]) + CASES
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        folder = Path(cls.directory.name)
        fixture = folder / "test.c"
        fixture.write_text(source)
        library = folder / ("test.dll" if os.name == "nt" else "test.so")
        command = [compiler, "-O2", "-shared", str(fixture), "-o", str(library)]
        if os.name == "nt":
            command += ["-nostdlib", "-fuse-ld=lld", "-Xlinker", "/noentry",
                        "-Xlinker", "/nodefaultlib"]
        else:
            command += ["-fPIC"]
        subprocess.run(command, check=True, capture_output=True, text=True)
        cls.library = ctypes.CDLL(str(library))
        cls.addClassCleanup(lambda: _ctypes.FreeLibrary(cls.library._handle)
                            if os.name == "nt" else None)
        cls.library.run_case.argtypes = [ctypes.c_int, ctypes.c_int]
        cls.library.run_case.restype = ctypes.c_int

    def test_proxy_start_then_advertising_failure_completes(self):
        self.assertNotEqual(self.library.run_case(0, 0), 0, "Old SDK must reproduce the stall")
        self.assertEqual(self.library.run_case(0, 1), 0)

    def test_fresh_failure_keeps_start_error_without_duplicate_completion(self):
        for fixed in (0, 1):
            self.assertEqual(self.library.run_case(1, fixed), 0)

    def test_success_waits_for_real_advertising_completion(self):
        for fixed in (0, 1):
            self.assertEqual(self.library.run_case(2, fixed), 0)

    def test_other_advertising_errors_are_preserved(self):
        self.assertEqual(self.library.run_case(3, 1), 0)


if __name__ == "__main__":
    unittest.main()
