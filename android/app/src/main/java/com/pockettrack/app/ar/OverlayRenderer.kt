package com.pockettrack.app.ar

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer

/**
 * Draws everything on top of the camera image: detected floor planes, tracked feature points and
 * the placeable cube (with a floor shadow and a "front" arrow so rotation is readable).
 * None of this goes into the recorded video.
 */
class OverlayRenderer {
    private val flat = FlatShader()

    private var litProgram = 0
    private var litPosition = 0
    private var litNormal = 0
    private var litMvp = 0
    private var litModel = 0
    private var litColor = 0

    private val cubeTris: FloatBuffer   // xyz + normal, 36 vertices
    private val cubeEdges: FloatBuffer  // xyz, 24 vertices (12 lines) + front arrow
    private val cubeEdgeCount: Int
    private val shadowQuad = GlUtil.floatBuffer(
        floatArrayOf(-0.5f, 0f, -0.5f, 0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f)
    )
    private var planeBuffer = GlUtil.floatBuffer(300)
    private val grid: FloatBuffer
    private val gridCount: Int

    private val mvp = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)

    init {
        // Unit cube with its origin at the centre of the bottom face: x,z in [-0.5, 0.5], y in [0, 1].
        // Each face: centre + n/2, tangents u,v with u x v = n so triangles wind counter-clockwise.
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
        )
        val tris = ArrayList<Float>()
        for (f in faces) {
            val cx = f[0] * 0.5f
            val cy = 0.5f + f[1] * 0.5f
            val cz = f[2] * 0.5f
            fun corner(su: Float, sv: Float) {
                tris += cx + 0.5f * (su * f[3] + sv * f[6])
                tris += cy + 0.5f * (su * f[4] + sv * f[7])
                tris += cz + 0.5f * (su * f[5] + sv * f[8])
                tris += f[0]; tris += f[1]; tris += f[2]
            }
            corner(-1f, -1f); corner(1f, -1f); corner(1f, 1f)
            corner(-1f, -1f); corner(1f, 1f); corner(-1f, 1f)
        }
        cubeTris = GlUtil.floatBuffer(tris.toFloatArray())

        val c = arrayOf(
            floatArrayOf(-0.5f, 0f, -0.5f), floatArrayOf(0.5f, 0f, -0.5f),
            floatArrayOf(0.5f, 0f, 0.5f), floatArrayOf(-0.5f, 0f, 0.5f),
            floatArrayOf(-0.5f, 1f, -0.5f), floatArrayOf(0.5f, 1f, -0.5f),
            floatArrayOf(0.5f, 1f, 0.5f), floatArrayOf(-0.5f, 1f, 0.5f),
        )
        val edgeIdx = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        val edges = ArrayList<Float>()
        for (i in edgeIdx) edges.addAll(c[i].toList())
        // Arrow on the floor pointing out of the +Z ("front") face.
        val arrow = floatArrayOf(
            0f, 0.005f, 0.5f, 0f, 0.005f, 0.9f,
            0f, 0.005f, 0.9f, -0.12f, 0.005f, 0.75f,
            0f, 0.005f, 0.9f, 0.12f, 0.005f, 0.75f,
        )
        edges.addAll(arrow.toList())
        cubeEdges = GlUtil.floatBuffer(edges.toFloatArray())
        cubeEdgeCount = edges.size / 3

        // 4 x 4 m floor grid with 25 cm cells, centred on the anchor (same as Blender's floor).
        val g = ArrayList<Float>()
        for (i in -8..8) {
            val v = i * 0.25f
            g.addAll(listOf(v, 0f, -2f, v, 0f, 2f, -2f, 0f, v, 2f, 0f, v))
        }
        grid = GlUtil.floatBuffer(g.toFloatArray())
        gridCount = g.size / 3
    }

    fun drawFloorGrid(anchor: FloatArray, view: FloatArray, proj: FloatArray) {
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, anchor, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glLineWidth(2f)
        flat.draw(GLES20.GL_LINES, grid, 3, gridCount, mvp, 0.3f, 0.9f, 1f, 0.55f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun create() {
        flat.create()
        litProgram = GlUtil.program(
            """
            uniform mat4 u_Mvp;
            uniform mat4 u_Model;
            attribute vec3 a_Position;
            attribute vec3 a_Normal;
            varying vec3 v_Normal;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position, 1.0);
                v_Normal = (u_Model * vec4(a_Normal, 0.0)).xyz;
            }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec4 u_Color;
            varying vec3 v_Normal;
            void main() {
                vec3 n = normalize(v_Normal);
                float light = 0.45 + 0.55 * max(dot(n, normalize(vec3(0.4, 1.0, 0.3))), 0.0);
                gl_FragColor = vec4(u_Color.rgb * light, u_Color.a);
            }
            """.trimIndent()
        )
        litPosition = GLES20.glGetAttribLocation(litProgram, "a_Position")
        litNormal = GLES20.glGetAttribLocation(litProgram, "a_Normal")
        litMvp = GLES20.glGetUniformLocation(litProgram, "u_Mvp")
        litModel = GLES20.glGetUniformLocation(litProgram, "u_Model")
        litColor = GLES20.glGetUniformLocation(litProgram, "u_Color")
    }

    fun drawPlanes(planes: Collection<Plane>, view: FloatArray, proj: FloatArray) {
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val poly = plane.polygon
            val n = poly.limit() / 2
            if (n < 3) continue
            if (planeBuffer.capacity() < n * 3) planeBuffer = GlUtil.floatBuffer(n * 3 * 2)
            planeBuffer.clear()
            for (i in 0 until n) {
                planeBuffer.put(poly.get(i * 2)); planeBuffer.put(0f); planeBuffer.put(poly.get(i * 2 + 1))
            }
            plane.centerPose.toMatrix(model, 0)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            flat.draw(GLES20.GL_TRIANGLE_FAN, planeBuffer, 3, n, mvp, 0.2f, 0.85f, 1f, 0.16f)
            flat.draw(GLES20.GL_LINE_LOOP, planeBuffer, 3, n, mvp, 0.2f, 0.85f, 1f, 0.6f)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** [points] is ARCore's point cloud buffer: x, y, z, confidence per point (world space). */
    fun drawPoints(points: FloatBuffer, view: FloatArray, proj: FloatArray) {
        val count = points.remaining() / 4
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        flat.draw(GLES20.GL_POINTS, points, 4, count, mvp, 1f, 0.92f, 0.2f, 1f, 9f)
    }

    /**
     * [anchor] is the anchor's world matrix; [c] is a cube snapshot
     * (x, y, z, yaw, sizeX, sizeY, sizeZ) in anchor space.
     */
    fun drawCube(anchor: FloatArray, c: FloatArray, view: FloatArray, proj: FloatArray, recording: Boolean) {
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val yawDeg = Math.toDegrees(c[3].toDouble()).toFloat()

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Shadow on the floor (anchor y = 0) directly below the cube, so height is readable.
        System.arraycopy(anchor, 0, tmp, 0, 16)
        Matrix.translateM(tmp, 0, c[0], 0.002f, c[2])
        Matrix.rotateM(tmp, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(tmp, 0, c[4] * 1.08f, 1f, c[6] * 1.08f)
        Matrix.multiplyMM(mvp, 0, vp, 0, tmp, 0)
        GLES20.glDepthMask(false)
        flat.draw(GLES20.GL_TRIANGLE_STRIP, shadowQuad, 3, 4, mvp, 0f, 0f, 0f, 0.35f)
        GLES20.glDepthMask(true)

        // Body.
        System.arraycopy(anchor, 0, model, 0, 16)
        Matrix.translateM(model, 0, c[0], c[1], c[2])
        Matrix.rotateM(model, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, c[4], c[5], c[6])
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(litProgram)
        cubeTris.position(0)
        GLES20.glVertexAttribPointer(litPosition, 3, GLES20.GL_FLOAT, false, 24, cubeTris)
        cubeTris.position(3)
        GLES20.glVertexAttribPointer(litNormal, 3, GLES20.GL_FLOAT, false, 24, cubeTris)
        GLES20.glEnableVertexAttribArray(litPosition)
        GLES20.glEnableVertexAttribArray(litNormal)
        GLES20.glUniformMatrix4fv(litMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(litModel, 1, false, model, 0)
        GLES20.glUniform4f(litColor, 1f, 0.54f, 0.12f, 0.55f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
        GLES20.glDisableVertexAttribArray(litPosition)
        GLES20.glDisableVertexAttribArray(litNormal)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // Edges + front arrow (red while recording).
        GLES20.glLineWidth(3f)
        if (recording) {
            flat.draw(GLES20.GL_LINES, cubeEdges, 3, cubeEdgeCount, mvp, 1f, 0.25f, 0.2f, 1f)
        } else {
            flat.draw(GLES20.GL_LINES, cubeEdges, 3, cubeEdgeCount, mvp, 1f, 1f, 1f, 0.95f)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
