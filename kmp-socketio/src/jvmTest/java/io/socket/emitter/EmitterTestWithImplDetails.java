package io.socket.emitter;

import com.piasy.kmp.socketio.emitter.Emitter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class EmitterTestWithImplDetails {

    @Test
    public void offOnceFromOn1() {
        final Emitter emitter = new Emitter();
        final boolean[] calledOnce = new boolean[] {false};
        final Emitter.Listener once = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOnce[0] = true;
            }
        };
        final boolean[] calledOn = new boolean[] {false};
        final Emitter.Listener on = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOn[0] = true;
                emitter.off("tobi", once);
            }
        };

        // order doesn't matter
        emitter.on("tobi", on);
        emitter.once("tobi", once);

        emitter.emit("tobi");
        assertThat(calledOnce[0], is(true));
        assertThat(calledOn[0], is(true));
        calledOnce[0] = false;
        emitter.emit("tobi");
        assertThat(calledOnce[0], is(false));
    }

    @Test
    public void offOnceFromOn2() {
        final Emitter emitter = new Emitter();
        final boolean[] calledOnce = new boolean[] {false};
        final Emitter.Listener once = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOnce[0] = true;
            }
        };
        final boolean[] calledOn = new boolean[] {false};
        final Emitter.Listener on = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOn[0] = true;
                emitter.off("tobi", once);
            }
        };

        // order doesn't matter
        emitter.once("tobi", once);
        emitter.on("tobi", on);

        emitter.emit("tobi");
        assertThat(calledOnce[0], is(true));
        assertThat(calledOn[0], is(true));
        calledOnce[0] = false;
        emitter.emit("tobi");
        assertThat(calledOnce[0], is(false));
    }

    @Test
    public void offOnFromOnce1() {
        final Emitter emitter = new Emitter();
        final boolean[] calledOn = new boolean[] {false};
        final Emitter.Listener on = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOn[0] = true;
            }
        };
        final boolean[] calledOnce = new boolean[] {false};
        final Emitter.Listener once = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOnce[0] = true;
                emitter.off("tobi", on);
            }
        };

        // order doesn't matter
        emitter.once("tobi", once);
        emitter.on("tobi", on);

        emitter.emit("tobi");
        assertThat(calledOnce[0], is(true));
        assertThat(calledOn[0], is(true));
        calledOn[0] = false;
        emitter.emit("tobi");
        assertThat(calledOn[0], is(false));
    }

    @Test
    public void offOnFromOnce2() {
        final Emitter emitter = new Emitter();
        final boolean[] calledOn = new boolean[] {false};
        final Emitter.Listener on = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOn[0] = true;
            }
        };
        final boolean[] calledOnce = new boolean[] {false};
        final Emitter.Listener once = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOnce[0] = true;
                emitter.off("tobi", on);
            }
        };

        // order doesn't matter
        emitter.on("tobi", on);
        emitter.once("tobi", once);

        emitter.emit("tobi");
        assertThat(calledOnce[0], is(true));
        assertThat(calledOn[0], is(true));
        calledOn[0] = false;
        emitter.emit("tobi");
        assertThat(calledOn[0], is(false));
    }

    @Test
    public void offSelfFromEvent() {
        final Emitter emitter = new Emitter();
        final boolean[] exec = new boolean[] {false};
        final Emitter.Listener on = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                emitter.off("tobi", this);
                exec[0] = true;
            }
        };
        emitter.on("tobi", on);
        emitter.emit("tobi");
        assertTrue(exec[0]);

        final boolean[] calledOnce = new boolean[] {false};
        final Emitter.Listener once = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                calledOnce[0] = true;
                emitter.off("tobi", this);
            }
        };
        emitter.once("tobi", once);
        emitter.emit("tobi");
        assertTrue(calledOnce[0]);
    }
}
