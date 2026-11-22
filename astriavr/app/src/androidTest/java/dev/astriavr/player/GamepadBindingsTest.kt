package dev.astriavr.player

import android.content.Context
import android.view.KeyEvent
import junit.framework.TestCase

/**
 * 4.9 可配置手柄映射回归：**纯逻辑，不启动 Activity、不需要亮屏解锁**。
 *
 * 锁住三条契约：默认值等价于 4.8 的硬编码、同组冲突自动解绑、
 * 跨组允许同键复用（A 键既是确认也是视角回正）。
 */
class GamepadBindingsTest : TestCase() {

    private fun context(): Context =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context

    override fun setUp() {
        super.setUp()
        GamepadBindings.reset(context())
    }

    override fun tearDown() {
        GamepadBindings.reset(context())
        super.tearDown()
    }

    fun testDefaultsMatchOldHardcodedMapping() {
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, GamepadBindings.keyFor(BindAction.UP))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, GamepadBindings.keyFor(BindAction.DOWN))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, GamepadBindings.keyFor(BindAction.LEFT))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, GamepadBindings.keyFor(BindAction.RIGHT))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, GamepadBindings.keyFor(BindAction.CONFIRM))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, GamepadBindings.keyFor(BindAction.BACK))
        assertEquals(KeyEvent.KEYCODE_MENU, GamepadBindings.keyFor(BindAction.MENU))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, GamepadBindings.keyFor(BindAction.PLAYLIST))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, GamepadBindings.keyFor(BindAction.TOGGLE))
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, GamepadBindings.keyFor(BindAction.EXIT))
        assertEquals(KeyEvent.KEYCODE_BUTTON_R2, GamepadBindings.keyFor(BindAction.PARAMS))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, GamepadBindings.keyFor(BindAction.PLAY_PAUSE))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, GamepadBindings.keyFor(BindAction.IPD_DOWN))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L1, GamepadBindings.keyFor(BindAction.SPEED_DOWN))
        assertEquals(KeyEvent.KEYCODE_BUTTON_R1, GamepadBindings.keyFor(BindAction.SPEED_UP))
    }

    fun testBindPersistsAcrossReload() {
        GamepadBindings.bind(context(), BindAction.CONFIRM, KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals(KeyEvent.KEYCODE_BUTTON_R2, GamepadBindings.keyFor(BindAction.CONFIRM))
        GamepadBindings.reload(context())
        assertEquals(KeyEvent.KEYCODE_BUTTON_R2, GamepadBindings.keyFor(BindAction.CONFIRM))
    }

    fun testReverseLookupUsesScope() {
        assertEquals(BindAction.CONFIRM,
            GamepadBindings.actionFor(KeyEvent.KEYCODE_BUTTON_A, BindScope.NAV))
        assertEquals(BindAction.RECENTER,
            GamepadBindings.actionFor(KeyEvent.KEYCODE_BUTTON_A, BindScope.PLAYER))
        assertEquals(BindAction.TOGGLE,
            GamepadBindings.actionFor(KeyEvent.KEYCODE_BUTTON_Y, BindScope.NAV))
        assertEquals(BindAction.IPD_UP,
            GamepadBindings.actionFor(KeyEvent.KEYCODE_BUTTON_Y, BindScope.PLAYER))
    }

    fun testConflictUnbindsWithinSameScope() {
        val before = GamepadBindings.keyFor(BindAction.UP)
        val result = GamepadBindings.bind(context(), BindAction.CONFIRM, before)
        assertEquals(BindAction.UP, result.replaced)
        assertEquals(before, GamepadBindings.keyFor(BindAction.CONFIRM))
        assertEquals(GamepadBindings.NO_KEY, GamepadBindings.keyFor(BindAction.UP))
        // 反查只应命中新绑定的功能，旧功能已经彻底让出这个键。
        assertEquals(BindAction.CONFIRM, GamepadBindings.actionFor(before, BindScope.NAV))
    }

    fun testCrossScopeReuseIsAllowed() {
        // 把播放页「视角回正」也绑到导航组的「确认」键上：不应解绑导航功能。
        val navKey = GamepadBindings.keyFor(BindAction.CONFIRM)
        val result = GamepadBindings.bind(context(), BindAction.RECENTER, navKey)
        assertNull(result.replaced)
        assertEquals(navKey, GamepadBindings.keyFor(BindAction.CONFIRM))
        assertEquals(navKey, GamepadBindings.keyFor(BindAction.RECENTER))
    }

    fun testCrossScopeConflictDoesNotUnbindOtherGroup() {
        val upKey = GamepadBindings.keyFor(BindAction.UP)
        val result = GamepadBindings.bind(context(), BindAction.SPEED_DOWN, upKey)
        assertNull(result.replaced)
        assertEquals(upKey, GamepadBindings.keyFor(BindAction.UP))
    }

    fun testResetRestoresDefaults() {
        GamepadBindings.bind(context(), BindAction.BACK, KeyEvent.KEYCODE_BUTTON_C)
        GamepadBindings.bind(context(), BindAction.LEFT, KeyEvent.KEYCODE_BUTTON_Z)
        GamepadBindings.reset(context())
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, GamepadBindings.keyFor(BindAction.BACK))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, GamepadBindings.keyFor(BindAction.LEFT))
    }

    /** 4.10：参数键默认 R2，必须不与任何既有 NAV / PLAYER 功能撞键。 */
    fun testParamsDefaultKeyIsUniqueAcrossBothScopes() {
        val key = KeyEvent.KEYCODE_BUTTON_R2
        val collisions = BindAction.values().filter {
            it !== BindAction.PARAMS && GamepadBindings.keyFor(it) == key
        }
        assertTrue("R2 被其它功能占用：$collisions", collisions.isEmpty())
    }

    fun testKeyLabelsAreStable() {
        assertEquals("A", GamepadBindings.keyLabel(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("↑", GamepadBindings.keyLabel(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("Start", GamepadBindings.keyLabel(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals("—", GamepadBindings.keyLabel(GamepadBindings.NO_KEY))
        assertEquals("#188", GamepadBindings.keyLabel(188))
    }

    /** 真机发现：廉价蓝牙手柄 / VR 遥控器只发媒体键或字母键，这两类必须可读且可绑定。 */
    fun testMediaAndLetterKeysAreReadableAndBindable() {
        assertEquals("▶/⏸", GamepadBindings.keyLabel(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals("⏭", GamepadBindings.keyLabel(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals("⏮", GamepadBindings.keyLabel(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals("A", GamepadBindings.keyLabel(KeyEvent.KEYCODE_A))
        assertEquals("Z", GamepadBindings.keyLabel(KeyEvent.KEYCODE_Z))
        assertEquals("0", GamepadBindings.keyLabel(KeyEvent.KEYCODE_0))
        assertFalse(GamepadBindings.isSystemKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        // 副标题仍显示原始 keyCode，字母 A(29) 与手柄 A(96) 不会混淆。
        assertEquals("keyCode 29", GamepadBindings.keyDetail(KeyEvent.KEYCODE_A))
    }

    fun testSystemKeysAreNotBindable() {
        assertTrue(GamepadBindings.isSystemKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(GamepadBindings.isSystemKey(KeyEvent.KEYCODE_POWER))
        assertFalse(GamepadBindings.isSystemKey(KeyEvent.KEYCODE_BUTTON_A))
    }

    fun testEveryActionHasADefaultAndATitle() {
        for (action in BindAction.values()) {
            assertTrue("${action.name} needs a real default keyCode", action.defaultKey > 0)
            assertTrue("${action.name} needs a title", action.title().isNotBlank())
        }
        assertEquals(2, BindScope.values().size)
    }
}
