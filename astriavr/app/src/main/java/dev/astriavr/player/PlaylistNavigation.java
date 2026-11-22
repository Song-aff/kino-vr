package dev.astriavr.player;

/** D-pad key/HAT deduplication, with repeat for held directions. Retain held bits on menu close. */
public final class PlaylistNavigation {
    /** 4.14：左右 = 逐条切换（±1），上下 = 整页翻（±VrPlaylistView.PAGE）。 */
    public static final int LEFT = 1, RIGHT = 2, CONFIRM = 4, PREV_PAGE = 8, NEXT_PAGE = 16;
    private static final int STEP = LEFT | RIGHT, PAGE = PREV_PAGE | NEXT_PAGE, DIRECTIONS = STEP | PAGE;
    private int keys, hat, repeatDirection;
    private long repeatAt;
    public boolean isNeutral() { return (keys | hat) == 0; }
    public int key(int action, boolean down, boolean repeated, long now) {
        int before = keys | hat;
        if (down) keys |= action; else keys &= ~action;
        updateRepeat(now);
        return down && !repeated ? fresh((keys | hat) & ~before) : 0;
    }
    /** 左右逐条、上下整页翻，确认只来自确认键。
     * 4.9 前把「上」当确认（一按就开播）；4.13 把上下做成 ±1 逐条，被用户否掉
     * （「那我不会左右吗」）—— 逐条留给左右键，上下整页跳。斜向取偏转更大的
     * 单轴，与 MenuAxisRouter 一致，避免上下左右打架。 */
    public int hat(float x, float y, long now) {
        int before = keys | hat;
        float ax = x < 0 ? -x : x, ay = y < 0 ? -y : y;
        int dir = 0;
        if (ax >= .5f || ay >= .5f) {
            if (ax >= ay) dir = x < 0 ? LEFT : RIGHT;
            else dir = y < 0 ? PREV_PAGE : NEXT_PAGE;
        }
        hat = dir;
        updateRepeat(now);
        return fresh((keys | hat) & ~before);
    }
    private int fresh(int mask) {
        if ((mask & CONFIRM) != 0) return CONFIRM;
        return suppressed() ? 0 : mask;
    }
    /** 同轴对向按住（左+右 / 上+下）互相抵消，不触发；跨轴互不影响。 */
    private boolean suppressed() {
        int held = keys | hat;
        return (held & STEP) == STEP || (held & PAGE) == PAGE;
    }
    private void updateRepeat(long now) {
        int direction = live();
        if (repeatDirection != direction) { repeatDirection = direction; repeatAt = now + 350; }
    }
    private int live() {
        int held = keys | hat;
        if ((held & STEP) == STEP) held &= ~STEP;
        if ((held & PAGE) == PAGE) held &= ~PAGE;
        return held & DIRECTIONS;
    }
    public int repeat(long now) {
        if (repeatDirection == 0 || now < repeatAt) return 0;
        repeatAt = now + 180;
        return repeatDirection;
    }
    public boolean hasRepeat() { return repeatDirection != 0; }
}
