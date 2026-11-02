# SPDX-License-Identifier: GPL-3.0-or-later
"""Builds a Blender scene from a PocketTrack recording (track.json + video.mp4).

ARCore uses +Y up; Blender uses +Z up. A point maps as (x, y, z) -> (x, -z, y).
The camera conventions already agree (looks down local -Z, +Y up), so camera matrices only
get the world conversion. The cube is built Z-up, so its matrix is conjugated instead.
"""

import json
import os
import struct
import zipfile

import bpy
from mathutils import Euler, Matrix, Quaternion, Vector

from .receiver import ALLOWED_FILES

AR_TO_BLENDER = Matrix(((1, 0, 0, 0), (0, 0, -1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))
SENSOR_WIDTH = 36.0


def resolve_recording(path):
    """Accepts a recording folder, its track.json, or an exported .zip. Returns the folder."""
    path = os.path.abspath(bpy.path.abspath(path))
    if os.path.isdir(path):
        folder = path
    elif path.lower().endswith(".zip"):
        folder = _extract_zip(path)
    elif os.path.basename(path).lower() == "track.json":
        folder = os.path.dirname(path)
    else:
        raise ValueError("Pick a PocketTrack .zip, a track.json or a recording folder")
    if not os.path.isfile(os.path.join(folder, "track.json")):
        raise ValueError("No track.json in %s" % folder)
    return folder


def _extract_zip(zip_path):
    """Extracts only the known files (no paths from the archive are trusted)."""
    base = os.path.splitext(zip_path)[0]
    with zipfile.ZipFile(zip_path) as zf:
        members = [m for m in zf.infolist() if os.path.basename(m.filename) in ALLOWED_FILES]
        if not any(os.path.basename(m.filename) == "track.json" for m in members):
            raise ValueError("%s is not a PocketTrack export (no track.json)" % os.path.basename(zip_path))
        os.makedirs(base, exist_ok=True)
        for m in members:
            target = os.path.join(base, os.path.basename(m.filename))
            with zf.open(m) as src, open(target, "wb") as dst:
                while True:
                    chunk = src.read(1024 * 1024)
                    if not chunk:
                        break
                    dst.write(chunk)
    return base


def import_recording(path, scene=None, options=None):
    """Imports a recording into ``scene``. Returns a dict of the created objects."""
    options = options or {}
    scene = scene or bpy.context.scene
    folder = resolve_recording(path)
    with open(os.path.join(folder, "track.json"), "r", encoding="utf-8") as f:
        track = json.load(f)
    if track.get("format") != "pockettrack":
        raise ValueError("track.json is not a PocketTrack file")

    name = _title(folder, track)
    video = track["video"]
    width, height = int(video["width"]), int(video["height"])
    fps = int(round(video.get("fps", 30)))
    frames = track["frames"]
    if not frames:
        raise ValueError("The recording has no frames")

    coll = bpy.data.collections.new("PocketTrack %s" % name)
    scene.collection.children.link(coll)

    def link(obj):
        coll.objects.link(obj)
        return obj

    # Everything hangs off one empty at the floor anchor, so the whole track can be moved,
    # rotated or scaled to fit an existing scene.
    root = link(bpy.data.objects.new("%s_Origin" % name, None))
    root.empty_display_type = "ARROWS"
    root.empty_display_size = 0.3
    root["pockettrack"] = name
    root["pockettrack_folder"] = folder  # what "Remove" may delete from disk

    def child(obj, role):
        obj.parent = root
        obj["pockettrack_role"] = role
        return obj

    cam = child(_build_camera(name, track, width, height, link), "camera")
    _animate_camera(cam, frames)
    _animate_zoom(cam, frames)

    proxy = child(_build_cube(name, track["object"], link), "proxy")

    points = None
    if track.get("points") and options.get("points", True):
        points = child(_build_points(name, track["points"], link), "points")

    floor = child(_build_floor(name, link), "floor")

    depth_points = depth_mesh = None
    depth_path = os.path.join(folder, "depth.bin")
    if os.path.isfile(depth_path) and options.get("depth", True):
        pts = _read_depth(depth_path)
        if pts:
            depth_points = child(_build_depth_points(name, pts, link), "depth_points")
            depth_mesh = child(_build_depth_mesh(name, depth_points, link), "depth_mesh")

    clip = None
    video_path = os.path.join(folder, video.get("file", "video.mp4"))
    if os.path.isfile(video_path):
        clip = bpy.data.movieclips.load(video_path, check_existing=True)
        clip.frame_start = 1
        cam.data.show_background_images = True
        bg = cam.data.background_images.new()
        bg.source = "MOVIE_CLIP"
        bg.clip = clip
        bg.use_camera_clip = False
        bg.alpha = 1.0
        bg.frame_method = "FIT"
        bg.display_depth = "BACK"
        scene.active_clip = clip
        root["pockettrack_clip"] = clip.name
        if video.get("audio"):
            sound = _add_sound(scene, name, video_path)
            if sound:
                root["pockettrack_sound"] = sound

    markers, summary = _quality_markers(scene, name, frames)
    root["pockettrack_markers"] = "\n".join(markers)
    root["pockettrack_quality"] = summary

    # Trim set on the phone (non-destructive): frames stay where they are, the range shrinks.
    first, last = 1, len(frames)
    trim = _trim(folder, len(frames))
    if trim:
        first, last = trim[0] + 1, trim[1] + 1

    if options.get("set_scene", True):
        scene.camera = cam
        scene.render.resolution_x = width
        scene.render.resolution_y = height
        scene.render.resolution_percentage = 100
        scene.render.fps = fps
        scene.render.fps_base = 1.0
        scene.frame_start = first
        scene.frame_end = last
        scene.frame_set(first)

    return {"name": name, "root": root, "camera": cam, "proxy": proxy, "points": points,
            "floor": floor, "depth_points": depth_points, "depth_mesh": depth_mesh,
            "clip": clip, "frames": len(frames),
            "quality": summary, "trim": trim}


def _build_camera(name, track, width, height, link):
    c = track["camera"]
    data = bpy.data.cameras.new("%s_Camera" % name)
    data.sensor_fit = "HORIZONTAL"
    data.sensor_width = SENSOR_WIDTH
    data.lens = c["fx"] / width * SENSOR_WIDTH
    # Principal point offset. Blender's shift is in units of the sensor-fit side, which is the
    # width here (sensor_fit HORIZONTAL) - also for vertical videos, where width is the short side.
    data.shift_x = (width / 2.0 - c["cx"]) / float(width)
    data.shift_y = (c["cy"] - height / 2.0) / float(width)
    data.clip_start = 0.01
    data.clip_end = 1000.0
    return link(bpy.data.objects.new("%s_Camera" % name, data))


def _animate_camera(cam, frames):
    """Keys location + rotation for every frame (bulk, so long takes import quickly)."""
    cam.rotation_mode = "XYZ"
    count = len(frames)
    loc = [0.0] * (count * 3)
    rot = [0.0] * (count * 3)
    prev = None
    for i, fr in enumerate(frames):
        # fr = [t, px, py, pz, qx, qy, qz, qw, tracking]
        m = Quaternion((fr[7], fr[4], fr[5], fr[6])).to_matrix().to_4x4()
        m.translation = Vector((fr[1], fr[2], fr[3]))
        mw = AR_TO_BLENDER @ m
        e = mw.to_euler("XYZ", prev) if prev is not None else mw.to_euler("XYZ")
        prev = e
        t = mw.to_translation()
        loc[i * 3:i * 3 + 3] = (t.x, t.y, t.z)
        rot[i * 3:i * 3 + 3] = (e.x, e.y, e.z)
    _bulk_keys(cam, {"location": loc, "rotation_euler": rot}, count)


def _animate_zoom(cam, frames):
    """Digital zoom (frame[9], newer recordings) crops about the image centre: it multiplies the
    focal length and the principal-point offset. Keyed only when the zoom actually changes."""
    zooms = [fr[9] if len(fr) > 9 else 1.0 for fr in frames]
    data = cam.data
    if all(abs(z - zooms[0]) < 1e-4 for z in zooms):
        z = zooms[0]
        data.lens, data.shift_x, data.shift_y = data.lens * z, data.shift_x * z, data.shift_y * z
        return
    lens, sx, sy = data.lens, data.shift_x, data.shift_y
    _bulk_keys(data, {"lens": [lens * z for z in zooms],
                      "shift_x": [sx * z for z in zooms],
                      "shift_y": [sy * z for z in zooms]}, len(zooms), components=1)


def _cube_transform(key):
    """key = [frame, x, y, z, yaw, size_x, size_y, size_z] in ARCore anchor space."""
    _, x, y, z, yaw, sx, sy, sz = key[:8]
    return Vector((x, -z, y)), Euler((0.0, 0.0, yaw)), Vector((sx, sz, sy))


def _build_cube(name, obj_info, link):
    s = 0.5
    verts = [(-s, -s, 0), (s, -s, 0), (s, s, 0), (-s, s, 0), (-s, -s, 1), (s, -s, 1), (s, s, 1), (-s, s, 1)]
    faces = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    mesh = bpy.data.meshes.new("%s_Proxy" % name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    obj = link(bpy.data.objects.new("%s_Proxy" % name, mesh))
    obj.data.materials.append(_material("PocketTrack Proxy", (1.0, 0.54, 0.12, 1.0)))
    obj.show_wire = True

    keys = obj_info.get("keys") or [[0, 0, 0, 0, 0, 0.5, 0.5, 0.5]]
    loc, rot, scale = _cube_transform(keys[0])
    obj.location, obj.rotation_euler, obj.scale = loc, rot, scale
    if len(keys) > 1:  # the cube was moved while recording
        for key in keys:
            loc, rot, scale = _cube_transform(key)
            obj.location, obj.rotation_euler, obj.scale = loc, rot, scale
            frame = int(key[0]) + 1
            for path in ("location", "rotation_euler", "scale"):
                obj.keyframe_insert(path, frame=frame)
    return obj


def _build_points(name, points, link):
    mesh = bpy.data.meshes.new("%s_TrackedPoints" % name)
    mesh.from_pydata([(p[0], -p[2], p[1]) for p in points], [], [])
    mesh.update()
    obj = link(bpy.data.objects.new("%s_TrackedPoints" % name, mesh))
    obj.data.materials.append(_material("PocketTrack Points", (1.0, 0.92, 0.2, 1.0)))
    try:  # show the vertices as small spheres (vertex-only meshes are invisible in object mode)
        mod = obj.modifiers.new("Points", "NODES")
        mod.node_group = _points_node_group("PocketTrack Points", 0.008)
    except Exception as e:  # pragma: no cover - older/odd builds; mesh is still usable
        print("PocketTrack: point display modifier skipped:", e)
    return obj


def _points_node_group(group_name, radius):
    ng = bpy.data.node_groups.get(group_name)
    if ng:
        return ng
    ng = _new_group(group_name)
    gin = ng.nodes.new("NodeGroupInput")
    gout = ng.nodes.new("NodeGroupOutput")
    to_points = ng.nodes.new("GeometryNodeMeshToPoints")
    to_points.inputs["Radius"].default_value = radius
    gin.location, to_points.location, gout.location = (-300, 0), (0, 0), (300, 0)
    ng.links.new(gin.outputs[0], to_points.inputs["Mesh"])
    ng.links.new(to_points.outputs["Points"], gout.inputs[0])
    return ng


def _build_floor(name, link, size=4.0, cells=16):
    """A 4 x 4 m grid (25 cm squares) on the anchor floor: translucent cyan in the viewport so it
    reads against the footage, and a shadow catcher in Cycles renders."""
    n = cells + 1
    step = size / cells
    verts = [(-size / 2 + x * step, -size / 2 + y * step, 0.0) for y in range(n) for x in range(n)]
    faces = [(y * n + x, y * n + x + 1, (y + 1) * n + x + 1, (y + 1) * n + x)
             for y in range(cells) for x in range(cells)]
    mesh = bpy.data.meshes.new("%s_Floor" % name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    obj = link(bpy.data.objects.new("%s_Floor" % name, mesh))
    obj.data.materials.append(_material("PocketTrack Floor", (0.2, 0.75, 1.0, 0.3)))
    obj.show_wire = True  # grid lines on top of the translucent fill
    obj.hide_select = True  # don't grab the floor by accident while placing objects on it
    if hasattr(obj, "is_shadow_catcher"):
        obj.is_shadow_catcher = True  # Cycles: catches shadows of CG objects on the real floor
    return obj


def _read_depth(path):
    """depth.bin: little-endian float32 x, y, z per point (anchor space, ARCore axes)."""
    with open(path, "rb") as f:
        data = f.read()
    n = len(data) // 12
    vals = struct.unpack("<%df" % (n * 3), data[:n * 12])
    return [(vals[i], -vals[i + 2], vals[i + 1]) for i in range(0, n * 3, 3)]


def _build_depth_points(name, pts, link):
    """ARCore Depth samples as a point cloud (small cyan dots)."""
    mesh = bpy.data.meshes.new("%s_DepthPoints" % name)
    mesh.from_pydata(pts, [], [])
    mesh.update()
    obj = link(bpy.data.objects.new("%s_DepthPoints" % name, mesh))
    obj.data.materials.append(_material("PocketTrack Depth", (0.25, 0.8, 1.0, 1.0)))
    obj.hide_select = True
    try:
        mod = obj.modifiers.new("Points", "NODES")
        mod.node_group = _points_node_group("PocketTrack Depth Points", 0.006)
    except Exception as e:
        print("PocketTrack: depth point display skipped:", e)
    return obj


def _build_depth_mesh(name, source, link):
    """A rough surface wrapped around the depth points (Geometry Nodes), as a shadow catcher.
    It shares the points' mesh data, so cleaning up stray points by hand also fixes the mesh."""
    obj = link(bpy.data.objects.new("%s_DepthMesh" % name, source.data))
    obj.material_slots[0].link = "OBJECT"
    obj.material_slots[0].material = _material("PocketTrack Depth Mesh", (0.25, 0.8, 1.0, 0.25))
    obj.show_wire = True
    obj.hide_select = True
    if hasattr(obj, "is_shadow_catcher"):
        obj.is_shadow_catcher = True
    try:
        mod = obj.modifiers.new("Depth mesh", "NODES")
        mod.node_group = _depth_mesh_node_group()
    except Exception as e:
        print("PocketTrack: depth mesh skipped:", e)
    return obj


def _new_group(group_name, with_input_geometry=True, extra_inputs=()):
    ng = bpy.data.node_groups.new(group_name, "GeometryNodeTree")
    if hasattr(ng, "interface"):
        if with_input_geometry:
            ng.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
        for sock_name, sock_type in extra_inputs:
            ng.interface.new_socket(sock_name, in_out="INPUT", socket_type=sock_type)
        ng.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
    else:
        if with_input_geometry:
            ng.inputs.new("NodeSocketGeometry", "Geometry")
        for sock_name, sock_type in extra_inputs:
            ng.inputs.new(sock_type, sock_name)
        ng.outputs.new("NodeSocketGeometry", "Geometry")
    return ng


def _depth_mesh_node_group():
    ng = bpy.data.node_groups.get("PocketTrack Depth Mesh")
    if ng:
        return ng
    ng = _new_group("PocketTrack Depth Mesh")
    gin = ng.nodes.new("NodeGroupInput")
    gout = ng.nodes.new("NodeGroupOutput")
    to_points = ng.nodes.new("GeometryNodeMeshToPoints")
    to_points.inputs["Radius"].default_value = 0.05
    ng.links.new(gin.outputs[0], to_points.inputs["Mesh"])
    try:
        # Blender 4.3+: points -> signed distance grid -> mesh
        sdf = ng.nodes.new("GeometryNodePointsToSDFGrid")
        sdf.inputs["Radius"].default_value = 0.06
        sdf.inputs["Voxel Size"].default_value = 0.04
        grid_mesh = ng.nodes.new("GeometryNodeGridToMesh")
        ng.links.new(to_points.outputs["Points"], sdf.inputs["Points"])
        ng.links.new(sdf.outputs[0], grid_mesh.inputs["Grid"])
        last = grid_mesh
    except RuntimeError:
        # older Blender: points -> fog volume -> mesh
        vol = ng.nodes.new("GeometryNodePointsToVolume")
        vol.resolution_mode = "VOXEL_SIZE"
        vol.inputs["Voxel Size"].default_value = 0.04
        vol.inputs["Radius"].default_value = 0.06
        vmesh = ng.nodes.new("GeometryNodeVolumeToMesh")
        vmesh.resolution_mode = "GRID"
        vmesh.inputs["Threshold"].default_value = 0.1
        ng.links.new(to_points.outputs["Points"], vol.inputs["Points"])
        ng.links.new(vol.outputs["Volume"], vmesh.inputs["Volume"])
        last = vmesh
    ng.links.new(last.outputs[0], gout.inputs[0])
    for i, n in enumerate((gin, to_points, last, gout)):
        n.location = (i * 220 - 440, 0)
    return ng


def _add_sound(scene, name, video_path):
    """Puts the recording's audio on the timeline (Video Sequencer) so it plays with the scene."""
    try:
        se = scene.sequence_editor or scene.sequence_editor_create()
        strips = se.strips if hasattr(se, "strips") else se.sequences
        channel = 1 + max([s.channel for s in strips] or [0])
        strip = strips.new_sound("%s audio" % name, video_path, channel, 1)
        if hasattr(scene, "sync_mode"):
            scene.sync_mode = "AUDIO_SYNC"
        return strip.name
    except Exception as e:  # no audio stream, or a Blender without audio support
        print("PocketTrack: audio not added:", e)
        return None


def _quality_markers(scene, name, frames):
    """Timeline markers where tracking was weak or lost (frame[8]: 1 good, 2 weak, 0 lost).
    Short blips (< 3 frames) of weak tracking are ignored. Returns (marker names, summary)."""
    codes = [int(fr[8]) if len(fr) > 8 else 1 for fr in frames]
    runs, start = [], 0
    for i in range(1, len(codes) + 1):
        if i == len(codes) or codes[i] != codes[start]:
            if codes[start] != 1 and (codes[start] == 0 or i - start >= 3):
                runs.append((codes[start], start, i - 1))
            start = i
    names = []
    for code, a, b in runs:
        label = "lost" if code == 0 else "weak"
        marker = scene.timeline_markers.new("%s %s: tracking %s %d-%d" % (
            "x" if code == 0 else "!", name, label, a + 1, b + 1), frame=a + 1)
        names.append(marker.name)
    good = 100.0 * sum(1 for c in codes if c == 1) / max(len(codes), 1)
    weak = sum(1 for r in runs if r[0] == 2)
    lost = sum(1 for r in runs if r[0] == 0)
    summary = "%d%% good" % round(good)
    if weak or lost:
        summary += " · %d weak · %d lost (see timeline markers)" % (weak, lost)
    return names, summary


def _trim(folder, count):
    """(first, last) frame indices from meta.json's trim, or None."""
    try:
        with open(os.path.join(folder, "meta.json"), "r", encoding="utf-8") as f:
            meta = json.load(f)
        a, b = int(meta.get("trim_start", -1)), int(meta.get("trim_end", -1))
    except (OSError, ValueError):
        return None
    if 0 <= a <= b < count:
        return a, b
    return None


def remove_scan(root, delete_files):
    """Removes an imported scan from the file (objects, clip, sound, markers, collection) and, if
    asked, the files PocketTrack saved for it on this PC. Returns the folder it cleaned, if any."""
    scene_list = list(bpy.data.scenes)
    folder = root.get("pockettrack_folder", "")
    markers = set(filter(None, root.get("pockettrack_markers", "").split("\n")))
    clip_name = root.get("pockettrack_clip")
    sound_name = root.get("pockettrack_sound")
    collections = set(root.users_collection)

    objs = [root] + list(root.children_recursive)
    # Remember data by name: references can go stale once their objects are removed.
    datas = [("CAMERA" if o.type == "CAMERA" else "MESH", o.data.name) for o in objs if o.data is not None]
    for o in objs:
        bpy.data.objects.remove(o, do_unlink=True)
    for kind, data_name in datas:
        coll = bpy.data.cameras if kind == "CAMERA" else bpy.data.meshes
        d = coll.get(data_name)
        if d is not None and d.users == 0:
            coll.remove(d)
    if clip_name and clip_name in bpy.data.movieclips and bpy.data.movieclips[clip_name].users == 0:
        bpy.data.movieclips.remove(bpy.data.movieclips[clip_name])
    for sc in scene_list:
        for m in [m for m in sc.timeline_markers if m.name in markers]:
            sc.timeline_markers.remove(m)
        se = sc.sequence_editor
        if se and sound_name:
            strips = se.strips if hasattr(se, "strips") else se.sequences
            if sound_name in strips:
                strips.remove(strips[sound_name])
    for coll in collections:
        if coll.name.startswith("PocketTrack ") and not coll.objects and not coll.children:
            bpy.data.collections.remove(coll)

    if delete_files and folder and os.path.isfile(os.path.join(folder, "track.json")):
        # Only the files PocketTrack writes; the folder goes if nothing else is in it.
        for f in os.listdir(folder):
            if f in ALLOWED_FILES or f.endswith(".part"):
                os.remove(os.path.join(folder, f))
        if not os.listdir(folder):
            os.rmdir(folder)
        return folder
    return None


def _material(mat_name, color):
    mat = bpy.data.materials.get(mat_name)
    if mat is None:
        mat = bpy.data.materials.new(mat_name)
        mat.diffuse_color = color
        if color[3] < 1.0 and hasattr(mat, "surface_render_method"):
            mat.surface_render_method = "BLENDED"  # EEVEE: honour the alpha
    return mat


def _title(folder, track):
    """The name the user gave the scan in the app (meta.json), falling back to the folder id."""
    try:
        with open(os.path.join(folder, "meta.json"), "r", encoding="utf-8") as f:
            title = json.load(f).get("title", "").strip()
        if title:
            return title
    except (OSError, ValueError):
        pass
    return track.get("name") or os.path.basename(folder)


# ---------------------------------------------------------------- animation helpers

def _fcurves(obj):
    """F-curves of the object's action, for both slotted (4.4+) and legacy actions."""
    ad = obj.animation_data
    action = ad.action
    slot = getattr(ad, "action_slot", None)
    if slot is not None:
        try:
            from bpy_extras import anim_utils
            bag = anim_utils.action_get_channelbag_for_slot(action, slot)
            if bag is not None:
                return bag.fcurves
        except (ImportError, AttributeError):
            pass
    return action.fcurves


def _bulk_keys(idblock, channels, count, components=3):
    """channels: {data_path: flat list of count*components values}. One key per frame from 1.
    Works for objects and object data (e.g. a camera's lens)."""
    for path in channels:
        idblock.keyframe_insert(path, frame=1)  # creates the action/slot/f-curves the official way
    fcurves = _fcurves(idblock)
    frames = [float(i + 1) for i in range(count)]
    for path, values in channels.items():
        for axis in range(components):
            fc = fcurves.find(path, index=axis)
            co = [0.0] * (count * 2)
            co[0::2] = frames
            co[1::2] = values[axis::components]
            fc.keyframe_points.clear()
            fc.keyframe_points.add(count)
            fc.keyframe_points.foreach_set("co", co)
            for kp in fc.keyframe_points:
                kp.interpolation = "LINEAR"
            fc.update()
