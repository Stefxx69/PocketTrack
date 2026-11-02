"""Headless test for the PocketTrack add-on.

Run:  blender --background --factory-startup --python blender_addon/tests/test_addon.py

Builds a synthetic recording (camera orbiting a cube, ARCore conventions), imports it
from a folder, a .zip and over the Wi-Fi receiver, and checks that the imported camera
projects the cube exactly where the recorded intrinsics say it should.
"""

import json
import math
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile

import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Matrix, Quaternion, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import pockettrack  # noqa: E402
from pockettrack import importer, qr, ui  # noqa: E402

W, H, FPS, N = 64, 36, 30, 20
FX, FY, CX, CY = 50.0, 50.0, 33.0, 17.0
CUBE = [0, 0.3, 0.0, -0.2, 0.4, 0.5, 0.8, 0.6]  # frame, x, y, z, yaw, sx, sy, sz (ARCore)
failures = []


def check(cond, msg):
    print(("  ok  " if cond else "  FAIL ") + msg)
    if not cond:
        failures.append(msg)


def look_at_quat(eye, target):
    """ARCore camera rotation (x,y,z,w): -Z toward target, +Y up."""
    f = (target - eye).normalized()
    z = -f
    x = Vector((0, 1, 0)).cross(z).normalized()
    y = z.cross(x)
    m = Matrix((x, y, z)).transposed()
    q = m.to_quaternion()
    return [q.x, q.y, q.z, q.w]


def make_recording(folder, name, with_video, title=None, zoom=None, quality=None, trim=None):
    """zoom: optional function frame index -> digital zoom (recorded as the 10th frame value).
    quality: optional function frame index -> tracking code (1 good, 2 weak, 0 lost)."""
    os.makedirs(folder, exist_ok=True)
    target = Vector((CUBE[1], CUBE[2] + CUBE[6] / 2, CUBE[3]))  # cube centre, ARCore space
    frames, eyes = [], []
    for i in range(N):
        a = i / N * math.pi * 0.5
        eye = Vector((2 * math.sin(a), 1.4, 2 * math.cos(a)))
        eyes.append(eye)
        frames.append([i / FPS, eye.x, eye.y, eye.z] + look_at_quat(eye, target)
                      + [quality(i) if quality else 1] + ([zoom(i)] if zoom else []))
    proj = [0.0] * 16
    proj[0], proj[5] = 2 * FX / W, 2 * FY / H
    proj[8], proj[9] = 1 - 2 * CX / W, 2 * CY / H - 1
    track = {
        "format": "pockettrack", "version": 1, "name": name,
        "video": {"file": "video.mp4", "width": W, "height": H, "fps": FPS, "frame_count": N},
        "camera": {"fx": FX, "fy": FY, "cx": CX, "cy": CY, "projection": proj},
        "object": {"type": "cube", "origin": "bottom_center", "keys": [CUBE]},
        "frames": frames,
        "points": [[0.1, 0.0, 0.1, 0.9], [-0.5, 0.0, 0.3, 0.8], [0.2, 1.0, -0.4, 0.5]],
    }
    with open(os.path.join(folder, "track.json"), "w") as f:
        json.dump(track, f)
    with open(os.path.join(folder, "meta.json"), "w") as f:
        meta = {"name": name, "frames": N}
        if title:
            meta["title"] = title
        if trim:
            meta["trim_start"], meta["trim_end"] = trim
        json.dump(meta, f)
    if with_video:
        render_test_video(os.path.join(folder, "video.mp4"))
    return target, eyes


def render_test_video(path):
    scene = bpy.data.scenes.new("video_render")
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.resolution_x, scene.render.resolution_y = W, H
    scene.frame_start, scene.frame_end = 1, N
    cam = bpy.data.objects.new("vcam", bpy.data.cameras.new("vcam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    s = scene.render.image_settings
    if hasattr(s, "media_type"):
        s.media_type = "VIDEO"
    s.file_format = "FFMPEG"
    scene.render.ffmpeg.format = "MPEG4"
    scene.render.ffmpeg.codec = "H264"
    scene.render.filepath = path
    with bpy.context.temp_override(scene=scene):
        bpy.ops.render.render(animation=True, scene=scene.name)
    rendered = [f for f in os.listdir(os.path.dirname(path)) if f.endswith(".mp4") and f != "video.mp4"]
    if not os.path.isfile(path) and rendered:
        os.replace(os.path.join(os.path.dirname(path), rendered[0]), path)
    bpy.data.scenes.remove(scene)


def pixel_of(scene, cam, world_point):
    v = world_to_camera_view(scene, cam, world_point)
    return v.x * W, (1 - v.y) * H


def expected_pixel(eye_q, eye, p_ar, zoom=1.0):
    """Projects an ARCore-space point with the recorded intrinsics (image y down).
    Digital zoom crops about the image centre: focal and principal-point offset scale by zoom."""
    rot = Quaternion((eye_q[3], eye_q[0], eye_q[1], eye_q[2])).to_matrix()
    pc = rot.transposed() @ (p_ar - eye)
    cx = W / 2 + (CX - W / 2) * zoom
    cy = H / 2 + (CY - H / 2) * zoom
    return cx + FX * zoom * pc.x / -pc.z, cy - FY * zoom * pc.y / -pc.z


def verify_import(result, target, eyes):
    scene = bpy.context.scene
    cam, proxy = result["camera"], result["proxy"]
    check(result["frames"] == N, "frame count %d" % result["frames"])
    check(scene.frame_end == N and scene.render.fps == FPS, "scene range/fps")
    check((scene.render.resolution_x, scene.render.resolution_y) == (W, H), "render resolution")
    track = json.load(open(os.path.join(importer.resolve_recording(result["folder"]), "track.json")))
    corner_ar = Vector((CUBE[1] + 0.25, CUBE[2] + 0.8, CUBE[3] + 0.3))  # some point to reproject
    for i in (0, N // 2, N - 1):
        scene.frame_set(i + 1)
        bpy.context.view_layer.update()
        q = track["frames"][i][4:8]
        z = track["frames"][i][9] if len(track["frames"][i]) > 9 else 1.0
        tw = proxy.matrix_world @ Vector((0, 0, 0.5))  # proxy centre in world
        px, py = pixel_of(scene, cam, tw)
        ex, ey = expected_pixel(q, eyes[i], target, z)
        check(abs(px - ex) < 0.05 and abs(py - ey) < 0.05,
              "frame %d (zoom %.2f) cube centre at (%.2f, %.2f), expected (%.2f, %.2f)" % (i + 1, z, px, py, ex, ey))
        cw = importer.AR_TO_BLENDER @ corner_ar
        px, py = pixel_of(scene, cam, cw)
        ex, ey = expected_pixel(q, eyes[i], corner_ar, z)
        check(abs(px - ex) < 0.05 and abs(py - ey) < 0.05,
              "frame %d off-axis point at (%.2f, %.2f), expected (%.2f, %.2f)" % (i + 1, px, py, ex, ey))
    dims = proxy.dimensions
    check(abs(dims.x - 0.5) < 1e-4 and abs(dims.y - 0.6) < 1e-4 and abs(dims.z - 0.8) < 1e-4,
          "proxy dimensions %s" % (tuple(round(d, 4) for d in dims),))
    check(abs(proxy.matrix_world.translation.z - 0.0) < 1e-6, "proxy stands on the floor (z=0)")


def main():
    tmp = tempfile.mkdtemp(prefix="pockettrack_test_")
    print("temp dir", tmp)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    pockettrack.register()

    # 1) folder import, with a real H.264 video
    print("folder import")
    folder = os.path.join(tmp, "PT_test_folder")
    target, eyes = make_recording(folder, "PT_test_folder", with_video=True)
    check(os.path.isfile(os.path.join(folder, "video.mp4")), "test video rendered")
    result = importer.import_recording(folder)
    result["folder"] = folder
    verify_import(result, target, eyes)
    check(result["clip"] is not None and result["clip"].frame_duration == N,
          "movie clip loaded with %s frames" % (result["clip"] and result["clip"].frame_duration))
    cam_data = result["camera"].data
    check(cam_data.show_background_images and len(cam_data.background_images) == 1, "footage as camera background")

    # toggles
    ui._apply_import(bpy.context.scene, result)
    props = bpy.context.scene.pockettrack
    props.show_footage = False
    props.show_proxy = False
    check(not cam_data.show_background_images and result["proxy"].hide_viewport, "toggles hide footage + object")
    props.show_footage = True
    props.show_proxy = True
    check(cam_data.show_background_images and not result["proxy"].hide_viewport, "toggles show again")

    # 2) zip import (layout the phone exports: <name>/<files>)
    print("zip import")
    zsrc = os.path.join(tmp, "src", "PT_test_zip")
    make_recording(zsrc, "PT_test_zip", with_video=False, title="New scan (1)")
    zpath = os.path.join(tmp, "PT_test_zip.zip")
    with zipfile.ZipFile(zpath, "w") as z:
        for f in os.listdir(zsrc):
            z.write(os.path.join(zsrc, f), "PT_test_zip/" + f)
        z.writestr("../evil.txt", "should be ignored")
    r2 = importer.import_recording(zpath)
    check(r2["frames"] == N and r2["clip"] is None, "zip import without video")
    check(not os.path.exists(os.path.join(tmp, "evil.txt")), "zip path traversal ignored")
    check(r2["camera"].name.startswith("New scan (1)"), "scan name from the app used: " + r2["camera"].name)
    floor = r2["floor"]
    check(len(floor.data.vertices) == 289 and floor.show_wire and floor.active_material.diffuse_color[3] < 1,
          "floor is a visible 16x16 grid")

    # 2b) vertical (portrait) recording: taller than wide, principal point off-centre
    print("portrait import")
    global W, H, CX, CY
    saved = (W, H, CX, CY)
    W, H, CX, CY = 36, 64, 17.0, 33.0
    psrc = os.path.join(tmp, "src", "PT_test_portrait")
    target, eyes = make_recording(psrc, "PT_test_portrait", with_video=False)
    rp = importer.import_recording(psrc)
    rp["folder"] = psrc
    verify_import(rp, target, eyes)

    # 2c) portrait + digital zoom changing 1x -> 2.5x during the take (animated lens)
    print("zoom import")
    zsrc2 = os.path.join(tmp, "src", "PT_test_zoom")
    target, eyes = make_recording(zsrc2, "PT_test_zoom", with_video=False, zoom=lambda i: 1 + 1.5 * i / (N - 1))
    rz = importer.import_recording(zsrc2)
    rz["folder"] = zsrc2
    verify_import(rz, target, eyes)
    W, H, CX, CY = saved

    # 2d) tracking-quality markers, trim and Remove
    print("quality / trim / remove")
    wsrc = os.path.join(tmp, "src", "PT_test_extras")
    make_recording(wsrc, "PT_test_extras", with_video=False, title="Extras",
                   quality=lambda i: 2 if 5 <= i <= 9 else (0 if i == 12 else 1),
                   trim=(2, 15))
    rx = importer.import_recording(wsrc)
    scene = bpy.context.scene
    names = [m.name for m in scene.timeline_markers if m.name.split(" ")[1:2] == ["Extras:"]]
    check(any("weak 6-10" in n for n in names) and any("lost 13-13" in n for n in names),
          "quality markers: %s" % names)
    check("good" in rx["quality"], "quality summary: " + rx["quality"])
    check((scene.frame_start, scene.frame_end) == (3, 16), "trim sets frame range %d-%d" % (scene.frame_start, scene.frame_end))
    ui._apply_import(scene, rx)
    root = rx["root"]
    cleaned = importer.remove_scan(root, delete_files=True)
    check(bpy.data.objects.get("Extras_Camera") is None and bpy.data.objects.get("Extras_Origin") is None,
          "remove: objects gone")
    check(not any("Extras:" in m.name for m in scene.timeline_markers), "remove: markers gone")
    check(cleaned == wsrc and not os.path.exists(wsrc), "remove: files deleted from disk")
    check(bpy.data.collections.get("PocketTrack Extras") is None, "remove: collection gone")

    # 2e) ARCore Depth points -> point cloud + rough mesh
    print("depth")
    import struct
    dsrc = os.path.join(tmp, "src", "PT_test_depth")
    make_recording(dsrc, "PT_test_depth", with_video=False, title="Depth scan")
    # a 1 x 1 m patch of floor (y = 0) and a wall (z = -1), 5 cm spacing, ARCore axes
    pts = [(x * 0.05 - 0.5, 0.0, z * 0.05 - 0.5) for x in range(21) for z in range(21)]
    pts += [(x * 0.05 - 0.5, y * 0.05, -1.0) for x in range(21) for y in range(21)]
    with open(os.path.join(dsrc, "depth.bin"), "wb") as f:
        f.write(struct.pack("<%df" % (len(pts) * 3), *[c for p in pts for c in p]))
    rd = importer.import_recording(dsrc)
    check(rd["depth_points"] is not None and len(rd["depth_points"].data.vertices) == len(pts),
          "depth points imported (%d)" % len(pts))
    zs = sorted(v.co.z for v in rd["depth_points"].data.vertices)
    check(abs(zs[0]) < 1e-6 and abs(zs[-1] - 1.0) < 1e-5, "depth axes: floor at z=0, wall up to z=1")
    dm = rd["depth_mesh"]
    bpy.context.view_layer.update()
    ev = dm.evaluated_get(bpy.context.evaluated_depsgraph_get())
    nverts = len(ev.to_mesh().vertices)
    ev.to_mesh_clear()
    check(nverts > 100, "rough depth mesh built (%d verts)" % nverts)
    check(dm.is_shadow_catcher, "depth mesh is a shadow catcher")

    # 3) Wi-Fi receiver round trip, as the phone does it
    print("receiver")
    rsrc = os.path.join(tmp, "src", "PT_test_wifi")
    make_recording(rsrc, "PT_test_wifi", with_video=False, title="Wifi scan")
    save_dir = os.path.join(tmp, "received")
    from pockettrack.receiver import Receiver
    rec = Receiver(save_dir, 8765)
    ui._receiver = rec
    payload = json.loads(rec.qr_payload())
    check(payload["pt"] == 1 and payload["p"] == rec.port and payload["k"] == rec.pin, "QR payload " + rec.qr_payload())
    base = "http://127.0.0.1:%d" % rec.port

    def req(path, method="GET", data=None, pin=rec.pin):
        r = urllib.request.Request(base + path, data=data, method=method, headers={"X-PocketTrack-Pin": pin})
        try:
            return urllib.request.urlopen(r, timeout=5).status
        except urllib.error.HTTPError as e:
            return e.code

    check(req("/ping", pin="000000" if rec.pin != "000000" else "111111") == 403, "wrong PIN rejected")
    check(req("/ping") == 200, "ping ok")
    check(req("/upload?rec=PT_test_wifi&file=../../x.txt", "POST", b"x") == 400, "bad file name rejected")
    check(req("/upload?rec=..%2F..&file=track.json", "POST", b"x") == 400, "bad recording name rejected")
    for f in ("track.json", "meta.json"):
        with open(os.path.join(rsrc, f), "rb") as fh:
            check(req("/upload?rec=PT_test_wifi&file=" + f, "POST", fh.read()) == 200, "upload " + f)
    check(req("/done?rec=PT_test_wifi", "POST", b"") == 200, "done accepted")
    ui._tick()
    check(bpy.context.scene.pockettrack.camera is not None and
          bpy.context.scene.pockettrack.camera.name.startswith("Wifi scan"), "receiver import ran: " + ui._last_message)
    check(ui._receiver is None, "receiver stopped after import")
    check(os.path.isdir(os.path.join(save_dir, "Wifi scan")) and not os.path.exists(os.path.join(save_dir, "PT_test_wifi")),
          "received folder named after the scan")

    # library dropdown lists the save folder's scans by name; a second same-named scan gets " (2)"
    from pockettrack import receiver as rcv
    dup = os.path.join(save_dir, "PT_dup")
    shutil.copytree(os.path.join(save_dir, "Wifi scan"), dup)
    check(os.path.basename(rcv.name_folder_after_scan(dup)) == "Wifi scan (2)", "duplicate name gets (2)")
    ui._save_dir = lambda: save_dir
    items = ui._library_items(None, bpy.context)
    labels = [i[1] for i in items]
    check(any(l.startswith("Wifi scan —") for l in labels) and any(l.startswith("Wifi scan (2)") or "Wifi scan  (Wifi scan (2))" in l for l in labels),
          "library lists scans: %s" % labels)

    # 4) QR encoder sanity (decoding is checked separately in a browser with jsQR)
    m = qr.encode(rec.qr_payload())
    check(len(m) in (21, 25, 29, 33, 37, 41) and all(len(r) == len(m) for r in m), "QR size %d" % len(m))
    # Set POCKETTRACK_QR_DUMP=path.json to dump sample QR matrices for an external decoder check.
    dump = os.environ.get("POCKETTRACK_QR_DUMP")
    with open(dump or os.devnull, "w") as f:
        samples = {t: qr.encode(t) for t in (
            rec.qr_payload(),
            '{"pt":1,"h":["192.168.100.200","10.0.0.15","172.16.5.4"],"p":8765,"k":"004213"}',
            "hello",
            "x" * 200,
        )}
        json.dump({k: ["".join("1" if v else "0" for v in row) for row in mat] for k, mat in samples.items()}, f)

    pockettrack.unregister()
    shutil.rmtree(tmp, ignore_errors=True)
    print("\n%d failure(s)" % len(failures))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        import traceback
        traceback.print_exc()
        sys.exit(2)
