# SPDX-License-Identifier: GPL-3.0-or-later
"""Operators, the N-panel, the Wi-Fi QR overlay and the scene toggles."""

import json
import os

import bpy
import blf
import gpu
from bpy.props import BoolProperty, IntProperty, PointerProperty, StringProperty
from bpy_extras.io_utils import ImportHelper
from gpu_extras.batch import batch_for_shader

from . import importer, qr
from .receiver import Receiver, scan_title

_receiver = None
_qr_modules = None
_qr_visible = True
_draw_handle = None
_last_message = ""


def _prefs():
    addon = bpy.context.preferences.addons.get(__package__)
    return addon.preferences if addon else None


def _redraw_all():
    wm = bpy.context.window_manager
    if not wm:
        return
    for window in wm.windows:
        for area in window.screen.areas:
            area.tag_redraw()


# ---------------------------------------------------------------- scene state + toggles

def _set_visible(obj, visible):
    if obj is not None:
        obj.hide_viewport = not visible
        obj.hide_render = not visible


def _update_footage(self, context):
    if self.camera is not None and self.camera.type == "CAMERA":
        self.camera.data.show_background_images = self.show_footage


_library = {"key": None, "items": [("NONE", "No scans in this folder", "")]}


def _save_dir():
    prefs = _prefs()
    if prefs is not None:
        return bpy.path.abspath(prefs.save_dir)
    return os.path.join(os.path.expanduser("~"), "PocketTrack")


def _library_items(self, context):
    """Every scan (a folder with a track.json) in the save folder, newest first, by its name."""
    folder = _save_dir()
    try:
        key = (folder, os.stat(folder).st_mtime_ns)
    except OSError:
        key = (folder, None)
    if key == _library["key"]:
        return _library["items"]
    found = []
    try:
        for entry in os.scandir(folder):
            if entry.is_dir() and os.path.isfile(os.path.join(entry.path, "track.json")):
                meta = {}
                try:
                    with open(os.path.join(entry.path, "meta.json"), "r", encoding="utf-8") as f:
                        meta = json.load(f)
                except (OSError, ValueError):
                    pass
                title = scan_title(entry.path) or entry.name
                secs = int(meta.get("duration", 0))
                res = min(int(meta.get("width", 0)), int(meta.get("height", 0)))
                detail = "%d:%02d · %dp%s" % (secs // 60, secs % 60, res, " · depth" if meta.get("depth_points") else "")
                # Folders named after the scan (maybe with " (2)") show as-is; old ID folders as "name  (ID)".
                label = entry.name if entry.name.startswith(title) else "%s  (%s)" % (title, entry.name)
                found.append((entry.stat().st_mtime, entry.name, "%s — %s" % (label, detail), entry.path))
    except OSError:
        pass
    found.sort(reverse=True)
    items = [(name, label, path) for _, name, label, path in found]
    _library["key"] = key
    _library["items"] = items or [("NONE", "No scans in this folder", "")]
    return _library["items"]


class PocketTrackSceneProps(bpy.types.PropertyGroup):
    camera: PointerProperty(type=bpy.types.Object, name="Camera")
    proxy: PointerProperty(type=bpy.types.Object, name="Proxy")
    points: PointerProperty(type=bpy.types.Object, name="Points")
    floor: PointerProperty(type=bpy.types.Object, name="Floor")
    library_scan: bpy.props.EnumProperty(name="Scan", items=_library_items,
                                         description="Scans saved in the Save to folder")
    depth_points: PointerProperty(type=bpy.types.Object, name="Depth points")
    depth_mesh: PointerProperty(type=bpy.types.Object, name="Depth mesh")
    show_footage: BoolProperty(name="Footage", default=True, update=_update_footage,
                               description="Show the recorded video behind the camera view")
    show_proxy: BoolProperty(name="Object", default=True,
                             update=lambda self, ctx: _set_visible(self.proxy, self.show_proxy),
                             description="Show the cube you placed in the app")
    show_points: BoolProperty(name="Tracked points", default=True,
                              update=lambda self, ctx: _set_visible(self.points, self.show_points),
                              description="Show ARCore's tracked feature points")
    show_floor: BoolProperty(name="Floor", default=True,
                             update=lambda self, ctx: _set_visible(self.floor, self.show_floor),
                             description="Shadow-catcher floor plane at the anchor")
    show_depth_points: BoolProperty(name="Depth points", default=True,
                                    update=lambda self, ctx: _set_visible(self.depth_points, self.show_depth_points),
                                    description="Room points from ARCore Depth")
    show_depth_mesh: BoolProperty(name="Depth mesh", default=True,
                                  update=lambda self, ctx: _set_visible(self.depth_mesh, self.show_depth_mesh),
                                  description="Rough room surface from ARCore Depth (shadow catcher)")


def _scan_roots():
    return [o for o in bpy.data.objects if "pockettrack" in o.keys() and o.parent is None]


def _apply_root(scene, root):
    """Makes the scan under [root] the one the panel toggles act on."""
    props = scene.pockettrack
    roles = {c.get("pockettrack_role"): c for c in root.children}
    props.camera = roles.get("camera")
    props.proxy = roles.get("proxy")
    props.points = roles.get("points")
    props.floor = roles.get("floor")
    props.depth_points = roles.get("depth_points")
    props.depth_mesh = roles.get("depth_mesh")
    if props.camera is not None:
        scene.camera = props.camera
    props.show_footage = props.camera is None or props.camera.data.show_background_images
    for name, obj in (("show_proxy", props.proxy), ("show_points", props.points),
                      ("show_floor", props.floor), ("show_depth_points", props.depth_points),
                      ("show_depth_mesh", props.depth_mesh)):
        setattr(props, name, obj is None or not obj.hide_viewport)


def _active_root(scene):
    cam = scene.pockettrack.camera
    return cam.parent if cam is not None and cam.parent is not None else None


def _apply_import(scene, result):
    props = scene.pockettrack
    props.camera = result["camera"]
    props.proxy = result["proxy"]
    props.points = result["points"]
    props.floor = result["floor"]
    props.depth_points = result.get("depth_points")
    props.depth_mesh = result.get("depth_mesh")
    for name in ("show_footage", "show_proxy", "show_points", "show_floor", "show_depth_points", "show_depth_mesh"):
        setattr(props, name, True)


# ---------------------------------------------------------------- preferences

class PocketTrackPreferences(bpy.types.AddonPreferences):
    bl_idname = __package__

    save_dir: StringProperty(
        name="Save received recordings to", subtype="DIR_PATH",
        default=os.path.join(os.path.expanduser("~"), "PocketTrack"))
    port: IntProperty(name="Port", default=8765, min=1024, max=65000)
    stop_after_import: BoolProperty(name="Stop receiver after an import", default=True)

    def draw(self, context):
        col = self.layout.column()
        col.prop(self, "save_dir")
        col.prop(self, "port")
        col.prop(self, "stop_after_import")


# ---------------------------------------------------------------- import from file

class POCKETTRACK_OT_import(bpy.types.Operator, ImportHelper):
    """Import a PocketTrack recording (.zip exported from the phone, or a track.json)"""
    bl_idname = "pockettrack.import_recording"
    bl_label = "Import PocketTrack"
    bl_options = {"REGISTER", "UNDO"}

    filter_glob: StringProperty(default="*.zip;*.json", options={"HIDDEN"})
    tracked_points: BoolProperty(name="Tracked points", default=True)
    set_scene: BoolProperty(name="Set scene camera, resolution, fps and frame range", default=True)

    def execute(self, context):
        try:
            result = importer.import_recording(
                self.filepath, context.scene,
                {"points": self.tracked_points, "set_scene": self.set_scene})
        except Exception as e:
            self.report({"ERROR"}, "PocketTrack: %s" % e)
            return {"CANCELLED"}
        _apply_import(context.scene, result)
        self.report({"INFO"}, "Imported %s (%d frames)" % (result["name"], result["frames"]))
        return {"FINISHED"}


def _menu_import(self, context):
    self.layout.operator(POCKETTRACK_OT_import.bl_idname, text="PocketTrack (.zip / track.json)")


# ---------------------------------------------------------------- Wi-Fi receiver

def _tick():
    """Main-thread timer: imports recordings the receiver thread finished."""
    global _last_message
    if _receiver is None:
        return None
    imported = False
    while not _receiver.completed.empty():
        folder = _receiver.completed.get()
        scene = bpy.context.scene or bpy.data.scenes[0]
        try:
            result = importer.import_recording(folder, scene)
            _apply_import(scene, result)
            _last_message = "Imported %s (%d frames)" % (result["name"], result["frames"])
            imported = True
        except Exception as e:
            _last_message = "Import failed: %s" % e
            print("PocketTrack:", _last_message)
        _receiver.status = _last_message
    _redraw_all()
    prefs = _prefs()
    if imported and (prefs is None or prefs.stop_after_import):
        _stop_receiver()
        return None
    return 0.5


def _start_receiver():
    global _receiver, _qr_modules, _qr_visible, _draw_handle
    prefs = _prefs()
    save_dir = bpy.path.abspath(prefs.save_dir) if prefs else os.path.join(os.path.expanduser("~"), "PocketTrack")
    os.makedirs(save_dir, exist_ok=True)
    _receiver = Receiver(save_dir, prefs.port if prefs else 8765)
    _qr_modules = qr.encode(_receiver.qr_payload())
    _qr_visible = True
    if _draw_handle is None:
        _draw_handle = bpy.types.SpaceView3D.draw_handler_add(_draw_qr, (), "WINDOW", "POST_PIXEL")
    if not bpy.app.timers.is_registered(_tick):
        bpy.app.timers.register(_tick, first_interval=0.5)


def _stop_receiver():
    global _receiver, _draw_handle
    if _receiver is not None:
        _receiver.stop()
        _receiver = None
    if _draw_handle is not None:
        bpy.types.SpaceView3D.draw_handler_remove(_draw_handle, "WINDOW")
        _draw_handle = None
    _redraw_all()


class POCKETTRACK_OT_import_library(bpy.types.Operator):
    """Import the scan picked in the list (from the Save to folder)"""
    bl_idname = "pockettrack.import_library_scan"
    bl_label = "Import scan"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        name = context.scene.pockettrack.library_scan
        folder = os.path.join(_save_dir(), name)
        if name == "NONE" or not os.path.isfile(os.path.join(folder, "track.json")):
            self.report({"WARNING"}, "Pick a scan first")
            return {"CANCELLED"}
        try:
            result = importer.import_recording(folder, context.scene)
        except Exception as e:
            self.report({"ERROR"}, "PocketTrack: %s" % e)
            return {"CANCELLED"}
        _apply_import(context.scene, result)
        self.report({"INFO"}, "Imported %s (%d frames)" % (result["name"], result["frames"]))
        return {"FINISHED"}


class POCKETTRACK_OT_activate(bpy.types.Operator):
    """Make this scan the active one (its camera becomes the scene camera)"""
    bl_idname = "pockettrack.activate_scan"
    bl_label = "Use this scan"
    bl_options = {"REGISTER", "UNDO"}

    root_name: StringProperty()

    def execute(self, context):
        root = bpy.data.objects.get(self.root_name)
        if root is None:
            return {"CANCELLED"}
        _apply_root(context.scene, root)
        return {"FINISHED"}


class POCKETTRACK_OT_remove(bpy.types.Operator):
    """Remove this scan from the file, and optionally delete its received files from this PC"""
    bl_idname = "pockettrack.remove_scan"
    bl_label = "Remove scan"
    bl_options = {"REGISTER", "UNDO"}

    root_name: StringProperty()
    delete_files: BoolProperty(name="Also delete its files on this PC", default=True)

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self, width=420)

    def draw(self, context):
        root = bpy.data.objects.get(self.root_name)
        col = self.layout.column()
        col.label(text="Remove “%s” from this file?" % (root.get("pockettrack", "") if root else self.root_name))
        folder = root.get("pockettrack_folder", "") if root else ""
        if folder and os.path.isdir(folder):
            col.prop(self, "delete_files")
            row = col.row()
            row.enabled = self.delete_files
            row.label(text=folder, icon="FILE_FOLDER")
            if self.delete_files:
                col.label(text="The files can't be restored with Undo.", icon="ERROR")
        else:
            col.label(text="No files of it are left on this PC.", icon="INFO")

    def execute(self, context):
        root = bpy.data.objects.get(self.root_name)
        if root is None:
            return {"CANCELLED"}
        was_active = _active_root(context.scene) == root
        name = root.get("pockettrack", root.name)
        try:
            folder = importer.remove_scan(root, self.delete_files)
        except OSError as e:
            self.report({"ERROR"}, "Removed from the file, but deleting files failed: %s" % e)
            return {"FINISHED"}
        if was_active:
            others = _scan_roots()
            if others:
                _apply_root(context.scene, others[-1])
        self.report({"INFO"}, "Removed %s%s" % (name, " and deleted %s" % folder if folder else ""))
        return {"FINISHED"}


class POCKETTRACK_OT_receive_start(bpy.types.Operator):
    """Listen for the phone on this PC's Wi-Fi and show a QR code to scan in the app"""
    bl_idname = "pockettrack.receive_start"
    bl_label = "Receive from phone"

    def execute(self, context):
        global _last_message
        try:
            _start_receiver()
        except Exception as e:
            self.report({"ERROR"}, "Could not start receiver: %s" % e)
            return {"CANCELLED"}
        _last_message = ""
        _redraw_all()
        return {"FINISHED"}


class POCKETTRACK_OT_receive_stop(bpy.types.Operator):
    """Stop listening for the phone"""
    bl_idname = "pockettrack.receive_stop"
    bl_label = "Stop receiver"

    def execute(self, context):
        _stop_receiver()
        return {"FINISHED"}


class POCKETTRACK_OT_toggle_qr(bpy.types.Operator):
    """Show or hide the QR code overlay in the 3D viewport"""
    bl_idname = "pockettrack.toggle_qr"
    bl_label = "Toggle QR code"

    def execute(self, context):
        global _qr_visible
        _qr_visible = not _qr_visible
        _redraw_all()
        return {"FINISHED"}


def _uniform_shader():
    for name in ("UNIFORM_COLOR", "2D_UNIFORM_COLOR"):
        try:
            return gpu.shader.from_builtin(name)
        except (ValueError, TypeError):
            continue
    raise RuntimeError("No uniform colour shader")


def _rect(coords, x, y, w, h):
    coords += [(x, y), (x + w, y), (x + w, y + h), (x, y), (x + w, y + h), (x, y + h)]


def _draw_text(x, y, text, size, color=(1, 1, 1, 1)):
    try:
        blf.size(0, size)
    except TypeError:  # Blender < 4.0 wants a dpi argument
        blf.size(0, size, 72)
    blf.color(0, *color)
    blf.position(0, x, y, 0)
    blf.draw(0, text)


def _draw_qr():
    if _receiver is None or _qr_modules is None or not _qr_visible:
        return
    region = bpy.context.region
    n = len(_qr_modules)
    quiet = 4
    total = n + quiet * 2
    px = max(2, int(min(region.width, region.height) * 0.55 / total))
    side = px * total
    x0 = (region.width - side) // 2
    y0 = (region.height - side) // 2 + 20

    light, dark = [], []
    _rect(light, x0 - 10, y0 - 70, side + 20, side + 80)
    for y, row in enumerate(_qr_modules):
        for x, on in enumerate(row):
            if on:
                _rect(dark, x0 + (x + quiet) * px, y0 + side - (y + quiet + 1) * px, px, px)
    shader = _uniform_shader()
    gpu.state.blend_set("NONE")
    shader.bind()
    shader.uniform_float("color", (1, 1, 1, 1))
    batch_for_shader(shader, "TRIS", {"pos": light}).draw(shader)
    shader.uniform_float("color", (0, 0, 0, 1))
    batch_for_shader(shader, "TRIS", {"pos": dark}).draw(shader)

    _draw_text(x0 + 8, y0 - 24, "PocketTrack app > recording > Send to PC - scan QR code", 13, (0, 0, 0, 1))
    hosts = ", ".join(_receiver.hosts)
    _draw_text(x0 + 8, y0 - 46, "%s : %d    PIN %s" % (hosts, _receiver.port, _receiver.pin), 13, (0.2, 0.2, 0.2, 1))
    _draw_text(x0 + 8, y0 - 64, _receiver.status, 12, (0.85, 0.35, 0.0, 1))


# ---------------------------------------------------------------- panel

class POCKETTRACK_PT_panel(bpy.types.Panel):
    bl_label = "PocketTrack"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "PocketTrack"

    def draw(self, context):
        layout = self.layout
        props = context.scene.pockettrack

        box = layout.box()
        box.label(text="Get a recording", icon="IMPORT")
        if _receiver is None:
            box.operator(POCKETTRACK_OT_receive_start.bl_idname, icon="URL")
            if _last_message:
                box.label(text=_last_message)
        else:
            col = box.column(align=True)
            col.label(text=_receiver.status, icon="TIME")
            col.label(text="Address: %s:%d" % (_receiver.hosts[0], _receiver.port))
            col.label(text="PIN: %s" % _receiver.pin)
            row = box.row(align=True)
            row.operator(POCKETTRACK_OT_toggle_qr.bl_idname,
                         text="Hide QR" if _qr_visible else "Show QR", icon="HIDE_OFF")
            row.operator(POCKETTRACK_OT_receive_stop.bl_idname, text="Stop", icon="CANCEL")
        box.operator(POCKETTRACK_OT_import.bl_idname, text="Import .zip / track.json…", icon="FILE_FOLDER")
        prefs = _prefs()
        if prefs is not None:
            # Where Wi-Fi / QR transfers land (also in the add-on preferences).
            box.prop(prefs, "save_dir", text="Save to")
        # Scans already in that folder: pick one and bring it into the scene.
        row = box.row(align=True)
        row.prop(props, "library_scan", text="")
        row.operator(POCKETTRACK_OT_import_library.bl_idname, text="", icon="IMPORT")

        roots = _scan_roots()
        if roots:
            box = layout.box()
            box.label(text="Scans in this file", icon="OUTLINER_COLLECTION")
            active = _active_root(context.scene)
            for r in roots:
                row = box.row(align=True)
                op = row.operator(POCKETTRACK_OT_activate.bl_idname, text=r.get("pockettrack", r.name),
                                  icon="RADIOBUT_ON" if r == active else "RADIOBUT_OFF", depress=r == active)
                op.root_name = r.name
                op = row.operator(POCKETTRACK_OT_remove.bl_idname, text="", icon="TRASH")
                op.root_name = r.name

        box = layout.box()
        box.label(text="Imported track", icon="CAMERA_DATA")
        if props.camera is None:
            box.label(text="Nothing imported yet")
            return
        root = props.camera.parent
        box.label(text=props.camera.name.replace("_Camera", ""))
        if root is not None and root.get("pockettrack_quality"):
            box.label(text="Tracking: " + root["pockettrack_quality"], icon="TRACKING")
        col = box.column(align=True)
        col.prop(props, "show_footage", icon="FILE_MOVIE")
        col.prop(props, "show_proxy", icon="MESH_CUBE")
        if props.points is not None:
            col.prop(props, "show_points", icon="PARTICLE_POINT")
        if props.floor is not None:
            col.prop(props, "show_floor", icon="MESH_PLANE")
        if props.depth_points is not None:
            col.prop(props, "show_depth_points", icon="POINTCLOUD_DATA")
        if props.depth_mesh is not None:
            col.prop(props, "show_depth_mesh", icon="MESH_MONKEY")
        box.operator("view3d.view_camera", text="Look through camera", icon="VIEW_CAMERA")


classes = (
    PocketTrackSceneProps,
    PocketTrackPreferences,
    POCKETTRACK_OT_import,
    POCKETTRACK_OT_import_library,
    POCKETTRACK_OT_activate,
    POCKETTRACK_OT_remove,
    POCKETTRACK_OT_receive_start,
    POCKETTRACK_OT_receive_stop,
    POCKETTRACK_OT_toggle_qr,
    POCKETTRACK_PT_panel,
)


def register():
    for cls in classes:
        bpy.utils.register_class(cls)
    bpy.types.Scene.pockettrack = PointerProperty(type=PocketTrackSceneProps)
    bpy.types.TOPBAR_MT_file_import.append(_menu_import)


def unregister():
    _stop_receiver()
    if bpy.app.timers.is_registered(_tick):
        bpy.app.timers.unregister(_tick)
    bpy.types.TOPBAR_MT_file_import.remove(_menu_import)
    del bpy.types.Scene.pockettrack
    for cls in reversed(classes):
        bpy.utils.unregister_class(cls)
