package demo.b;

/** Module B: A calls this method, only B is recompiled by the test. */
public final class Provider {

    public String value() {
        return "B1";
    }
}
