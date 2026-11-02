# SPDX-License-Identifier: GPL-3.0-or-later
"""PocketTrack: import ARCore camera tracks recorded with the PocketTrack Android app."""

bl_info = {
    "name": "PocketTrack",
    "author": "Stefxx69",
    "version": (0, 1, 0),
    "blender": (4, 2, 0),
    "location": "View3D > Sidebar > PocketTrack, File > Import > PocketTrack",
    "description": "Import phone camera tracks (camera, footage, proxy cube, tracked points), over Wi-Fi or from a .zip",
    "category": "Import-Export",
}

from . import ui  # noqa: E402


def register():
    ui.register()


def unregister():
    ui.unregister()
