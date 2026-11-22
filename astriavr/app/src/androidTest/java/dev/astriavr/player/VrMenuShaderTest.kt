package dev.astriavr.player

import android.opengl.GLES20
import junit.framework.TestCase
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * H4/S4a：用**离屏 EGL pbuffer**真正编译并链接 VrView 的 GLSL。
 *
 * shader 错误只在运行期暴露，而 CI / 息屏状态下拿不到真机截图，所以这条测试
 * 自己创建 ES2 上下文把 VERTEX_SHADER + FRAGMENT_SHADER 编译链接一遍，
 * 并断言新增的三个菜单 uniform 没被编译器优化掉。不需要亮屏、不需要 Activity。
 */
class VrMenuShaderTest : TestCase() {

    fun testMenuShaderCompilesAndKeepsUniforms() {
        val egl = EGLContext.getEGL() as EGL10
        val display: EGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        assertTrue("eglInitialize", egl.eglInitialize(display, IntArray(2)))

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val configAttribs = intArrayOf(
            EGL10.EGL_SURFACE_TYPE, 0x0001,          // EGL_PBUFFER_BIT
            EGL10.EGL_RENDERABLE_TYPE, 0x0004,       // EGL_OPENGL_ES2_BIT
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_NONE)
        assertTrue("eglChooseConfig", egl.eglChooseConfig(display, configAttribs, configs, 1, count))
        assertTrue("no ES2 config", count[0] > 0)

        val surface: EGLSurface = egl.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 16, EGL10.EGL_NONE))
        val context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT,
            intArrayOf(0x3098, 2, EGL10.EGL_NONE))   // EGL_CONTEXT_CLIENT_VERSION = 2
        assertTrue("eglMakeCurrent", egl.eglMakeCurrent(display, surface, surface, context))

        try {
            val vertex = compile(egl, GLES20.GL_VERTEX_SHADER, VrRenderer.VERTEX_SHADER)
            val fragment = compile(egl, GLES20.GL_FRAGMENT_SHADER, VrRenderer.FRAGMENT_SHADER)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            assertEquals("link failed: ${GLES20.glGetProgramInfoLog(program)}",
                GLES20.GL_TRUE, linked[0])

            // 菜单采样与翻转隔离相关的 uniform 必须真的存在于链接后的 program 里，
            // 否则 VrRenderer 拿到的 location 会是 -1，菜单永远不会显示。
            assertTrue("uMenu optimized out", GLES20.glGetUniformLocation(program, "uMenu") >= 0)
            assertTrue("uMenuMode optimized out", GLES20.glGetUniformLocation(program, "uMenuMode") >= 0)
            assertTrue("uMenuAspect optimized out", GLES20.glGetUniformLocation(program, "uMenuAspect") >= 0)
            assertTrue("uScreenFlip missing", GLES20.glGetUniformLocation(program, "uScreenFlip") >= 0)
            // 4.10：浮动菜单平面必须在链接后的 program 里，否则球面模式下参数页不显示。
            assertTrue("uMenuHalfWidth optimized out",
                GLES20.glGetUniformLocation(program, "uMenuHalfWidth") >= 0)

            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        } finally {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroyContext(display, context)
            egl.eglDestroySurface(display, surface)
            egl.eglTerminate(display)
        }
    }

    private fun compile(egl: EGL10, type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            fail("GLSL compile failed: $log")
        }
        return shader
    }
}
